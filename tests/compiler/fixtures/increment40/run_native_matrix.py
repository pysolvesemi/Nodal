#!/usr/bin/env python3
"""Run independent transfer IR, optimization, target and public-source boundaries.

These are compiler/structural checks, not analog simulator or stability evidence.
"""
from __future__ import annotations
import argparse
import re
import subprocess
import tempfile
from pathlib import Path

PIPELINE = "--pass-pipeline=builtin.module(nodal-fold-analog-constants,canonicalize,cse,nodal-verify-analog-numeric)"


def fixture(body: str) -> str:
    return '''module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{sym_name = "TransferTop", metadata = {}}> ({
    "nodal.parameter"() <{sym_name = "P", type = f64, default_value = 2.0 : f64, variability = "symbolic", metadata = {}}> : () -> ()
    %p = "nodal.terminal"() <{name = "p", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{name = "n", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
      %one = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
      %two = "nodal.real_literal"() <{value = 2.0 : f64, metadata = {}}> : () -> f64
      %zero = "nodal.real_literal"() <{value = 0.0 : f64, metadata = {}}> : () -> f64
      %negative = "nodal.real_literal"() <{value = -0.5 : f64, metadata = {}}> : () -> f64
      %time = "nodal.real_literal"() <{value = 1.0e-3 : f64, metadata = {unit = "s"}}> : () -> f64
      %short = "nodal.real_literal"() <{value = 1.0e-6 : f64, metadata = {unit = "s"}}> : () -> f64
      %zero_time = "nodal.real_literal"() <{value = 0.0 : f64, metadata = {unit = "s"}}> : () -> f64
      %volts = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "V"}}> : () -> f64
      %param = "nodal.parameter_ref"() <{parameter = @P, metadata = {}}> : () -> f64
      %symbolic_time = "nodal.analog_mul"(%time, %param) <{metadata = {}}> : (f64, f64) -> f64
      %time2 = "nodal.analog_mul"(%time, %time) <{metadata = {}}> : (f64, f64) -> f64
      %signal = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> f64
      %dynamic = "nodal.analog_div"(%signal, %volts) <{metadata = {}}> : (f64, f64) -> f64
      %dynamic_time = "nodal.analog_mul"(%dynamic, %time) <{metadata = {}}> : (f64, f64) -> f64
''' + body + '''
    }) : () -> ()
  }) : () -> ()
}
'''


def transfer(kind="laplace_nd", numerator=None, denominator=None, timing=None,
             signal="%signal", result="filter", dimension="voltage"):
    n = ["%one"] if numerator is None else numerator
    d = (["%one", "%time"] if kind == "laplace_nd" else ["%one", "%negative"]) if denominator is None else denominator
    t = ([] if kind == "laplace_nd" else ["%time"]) if timing is None else timing
    operands = [signal, *n, *d, *t]
    order = "ascending_s" if kind == "laplace_nd" else "ascending_z_inverse"
    return (f'      %{result} = "nodal.analog_transfer"({", ".join(operands)}) '
            f'<{{transfer_kind = "{kind}", contract_version = "1", numerator_size = {len(n)} : i64, '
            f'denominator_size = {len(d)} : i64, operator_id = "TransferTop.{result}", '
            f'state_id = "TransferTop.{result}.state", owner = "TransferTop", '
            f'coefficient_order = "{order}", initialization = "simulator-default", '
            f'result_dimension = "{dimension}", metadata = {{semantic_path = "TransferTop.{result}"}}}}> '
            f': ({", ".join("f64" for _ in operands)}) -> f64\n')


def contribution(value="filter"):
    return (f'      "nodal.contribute"(%branch, %{value}) <{{kind = "potential", metadata = {{}}}}> '
            ': (!nodal.branch<"electrical">, f64) -> ()\n')


def run(nodalc: Path, translate: Path, source: Path | None = None) -> int:
    count = 0
    with tempfile.TemporaryDirectory(prefix="nodal-increment40-") as temporary:
        path = Path(temporary) / "case.mlir"

        def invoke(tool, text, *arguments):
            path.write_text(text)
            return subprocess.run([str(tool), *arguments, str(path)], capture_output=True,
                                  text=True, timeout=90, check=False)

        def positive(text):
            nonlocal count
            parsed = invoke(nodalc, text)
            assert parsed.returncode == 0, parsed.stderr
            optimized = invoke(nodalc, text, PIPELINE)
            assert optimized.returncode == 0, optimized.stderr
            replay = invoke(nodalc, optimized.stdout, PIPELINE)
            assert replay.returncode == 0 and replay.stdout == optimized.stdout, replay.stderr
            expected = text.count('"nodal.analog_transfer"')
            assert optimized.stdout.count('"nodal.analog_transfer"') == expected, optimized.stdout
            ids = re.findall(r'operator_id = "(TransferTop\.[^"]+)"', text)
            for identity in ids:
                assert f'operator_id = "{identity}"' in optimized.stdout
                assert f'state_id = "{identity}.state"' in optimized.stdout
            for line in optimized.stdout.splitlines():
                if '"nodal.analog_transfer"' in line:
                    assert "nodal.folded" not in line and "nodal.simplif" not in line, line
            outputs = []
            for ir in [parsed.stdout, optimized.stdout]:
                emitted = invoke(translate, ir, "--nodal-to-verilog-a")
                assert emitted.returncode == 0, emitted.stderr
                assert len(re.findall(r"(?:laplace_nd|zi_nd)\(", emitted.stdout)) == expected, emitted.stdout
                repeated = invoke(translate, ir, "--nodal-to-verilog-a")
                assert repeated.returncode == 0 and repeated.stdout == emitted.stdout
                outputs.append(emitted.stdout)
            count += 1
            return optimized.stdout, outputs[0], outputs[1]

        def negative(text, diagnostic):
            nonlocal count
            for tool, args in [(nodalc, ()), (nodalc, (PIPELINE,)),
                               (translate, ("--nodal-to-verilog-a",))]:
                result = invoke(tool, text, *args)
                assert result.returncode != 0 and diagnostic in result.stderr, result.stdout + result.stderr
                assert "endmodule" not in result.stdout, "partial target escaped failure"
            count += 1

        def reject_target(text, diagnostic):
            nonlocal count
            # Source identities remain target-neutral; backend declarations do not.
            parsed = invoke(nodalc, text)
            assert parsed.returncode == 0, parsed.stderr
            for ir in (text, parsed.stdout):
                result = invoke(translate, ir, "--nodal-to-verilog-a")
                assert result.returncode != 0 and diagnostic in result.stderr, result.stdout + result.stderr
                assert not result.stdout, "invalid declaration published partial HDL"
            count += 1

        for kind in ["laplace_nd", "zi_nd"]:
            _, va, after = positive(fixture(transfer(kind) + contribution()))
            assert f"transfer_0 = {kind}(V(p, n), '{{1}}" in va, va
            assert "V(p, n) <+ transfer_0;" in va and "V(p, n) <+ transfer_0;" in after
        for timing in [["%time"], ["%time", "%short"], ["%time", "%short", "%zero_time"]]:
            positive(fixture(transfer("zi_nd", timing=timing) + contribution()))
        for numerator, denominator in [(["%zero"], ["%one"]), (["%param"], ["%one", "%symbolic_time"]),
                                       (["%one", "%time", "%time2"], ["%negative", "%zero_time", "%time2"])]:
            _, va, _ = positive(fixture(transfer(numerator=numerator, denominator=denominator) + contribution()))
            if "%param" in numerator:
                assert "'{P}" in va and "P" in va, va
        positive(fixture(transfer("zi_nd", numerator=["%zero", "%one", "%zero", "%two"], denominator=["%negative", "%zero"])))
        positive(fixture(transfer(signal="%dynamic", dimension="1")))
        positive(fixture(transfer(signal="%volts")))
        # Sharing an SSA result must not duplicate state. Equal calls remain distinct.
        addition = '      %sum = "nodal.analog_add"(%filter, %filter) <{metadata = {}}> : (f64, f64) -> f64\n'
        _, va, _ = positive(fixture(transfer() + addition + contribution("sum")))
        assert "(transfer_0 + transfer_0)" in va, va
        _, va, _ = positive(fixture(transfer() + transfer(result="other")))
        assert "transfer_0 = laplace_nd" in va and "transfer_1 = laplace_nd" in va, va
        _, va, _ = positive(fixture(transfer() + transfer("zi_nd", signal="%filter", result="cascade") + contribution("cascade")))
        assert "transfer_1 = zi_nd(transfer_0," in va, va
        # Authored identifiers always win over compiler temporaries.
        collision = fixture(transfer(numerator=["%param"]) + contribution()).replace(
            'sym_name = "P"', 'sym_name = "transfer_0"').replace('parameter = @P', 'parameter = @transfer_0')
        _, va, _ = positive(collision)
        assert "real transfer_1;" in va and "'{transfer_0}" in va, va
        # Keywords fail for every emitted declaration kind, not just modules.
        for keyword in ("input", "output", "parameter", "wire", "endmodule"):
            ordinary = fixture(transfer() + contribution())
            reject_target(ordinary.replace('name = "p"', f'name = "{keyword}"'), "NODAL-BACKEND-NAMING-001")
            reject_target(ordinary.replace('sym_name = "P"', f'sym_name = "{keyword}"').replace(
                'parameter = @P', f'parameter = @{keyword}'), "NODAL-BACKEND-NAMING-001")
            named_branch = ordinary.replace('%branch = "nodal.branch"(%p, %n) <{metadata = {}}>',
                f'%branch = "nodal.branch"(%p, %n) <{{name = "{keyword}", metadata = {{}}}}>')
            reject_target(named_branch, "NODAL-BACKEND-NAMING-001")
            node = ordinary.replace('"nodal.terminal"() <{name = "p"',
                                    f'"nodal.node"() <{{name = "{keyword}"')
            reject_target(node, "NODAL-BACKEND-NAMING-001")
        # Parameters, terminals and named branches share one emitted namespace.
        duplicate = fixture(transfer()).replace('sym_name = "P"', 'sym_name = "p"').replace(
            'parameter = @P', 'parameter = @p')
        reject_target(duplicate, "NODAL-BACKEND-NAMING-002")
        for spelling in ("inputSignal", "myendmoduleBlock", "wire_value", "transfer_0"):
            positive(fixture(transfer() + contribution()).replace('name = "p"', f'name = "{spelling}"'))
        # Constant conditionals must retain the separately created state in either arm.
        boolean = '      %yes = "nodal.const_literal"() <{value = true, spelling = "1", metadata = {}}> : () -> i1\n'
        for kind in ("laplace_nd", "zi_nd"):
            for a, b in (("%filter", "%volts"), ("%volts", "%filter")):
                selected = (f'      %selected = "nodal.analog_select"(%yes, {a}, {b}) '
                            '<{metadata = {}}> : (i1, f64, f64) -> f64\n')
                positive(fixture(transfer(kind) + boolean + selected + contribution("selected")))
        # Native IDs must remain safe source-map text, including on raw parsing.
        for bad in ("TransferTop.filter ", "TransferTop.filter\\0Aline", "TransferTop.filter\\00nul", "TransferTop.filter\\7Fdel"):
            negative(fixture(transfer()).replace("TransferTop.filter", bad), "040-002")
        # A noise source and a transfer may not reuse the same source identity.
        density = ('      %v2 = "nodal.analog_mul"(%volts, %volts) <{metadata = {}}> : (f64, f64) -> f64\n'
                   '      %density = "nodal.analog_mul"(%v2, %time) <{metadata = {}}> : (f64, f64) -> f64\n')
        noise = ('      %noise = "nodal.analog_noise"(%density) '
                 '<{noise_kind = "white", contract_version = "1", noise_name = "test", '
                 'source_id = "TransferTop.noise", owner = "TransferTop", correlation = "independent", '
                 'analyses = ["noise"], result_dimension = "voltage", '
                 'metadata = {semantic_path = "TransferTop.noise"}}> : (f64) -> f64\n')
        for body in (density + noise + transfer(), transfer() + density + noise):
            positive(fixture(body))
            negative(fixture(body).replace("TransferTop.noise", "TransferTop.filter"), "040-002")
        typed = transfer().replace("-> f64", '-> !nodal.quantity<"real", "voltage">')
        positive(fixture(typed))
        baseline = fixture(transfer())
        for before, after, code in [
            ('contract_version = "1"', 'contract_version = "999"', "040-002"),
            ('transfer_kind = "laplace_nd"', 'transfer_kind = "laplace_zp"', "040-002"),
            ('owner = "TransferTop"', 'owner = "foreign"', "040-002"),
            ('state_id = "TransferTop.filter.state"', 'state_id = "foreign"', "040-002"),
            ('semantic_path = "TransferTop.filter"', 'semantic_path = "foreign"', "040-002"),
            ('numerator_size = 1 : i64', 'numerator_size = 0 : i64', "040-002"),
            ('numerator_size = 1 : i64', 'numerator_size = -1 : i64', "040-002"),
            ('denominator_size = 2 : i64', 'denominator_size = 9223372036854775807 : i64', "040-002"),
            ('coefficient_order = "ascending_s"', 'coefficient_order = "descending_s"', "040-006"),
            ('initialization = "simulator-default"', 'initialization = "zero"', "040-006"),
            ('result_dimension = "voltage"', 'result_dimension = "current"', "040-003"),
        ]:
            assert before in baseline
            negative(baseline.replace(before, after), code)
        for call, code in [
            (transfer(numerator=[]), "040-002"), (transfer(denominator=[]), "040-002"),
            (transfer(timing=["%time"]), "040-002"), (transfer("zi_nd", timing=[]), "040-002"),
            (transfer("zi_nd", timing=["%time"] * 4), "040-002"),
            (transfer(denominator=["%zero", "%time"]), "040-004"),
            (transfer(denominator=["%param", "%time"]), "040-006"),
            (transfer(numerator=["%volts"]), "040-003"),
            (transfer(denominator=["%one", "%one"]), "040-003"),
            (transfer(numerator=["%dynamic"]), "040-005"),
            (transfer(denominator=["%one", "%dynamic_time"]), "040-005"),
            (transfer("zi_nd", timing=["%symbolic_time"]), "040-006"),
            (transfer("zi_nd", timing=["%one"]), "040-003"),
            (transfer("zi_nd", timing=["%zero_time"]), "040-004"),
            (transfer("zi_nd", timing=["%time", "%zero_time"]), "040-004"),
        ]:
            negative(fixture(call), code)
        negative(fixture(transfer("zi_nd", timing=["%time", "%short", "%zero_time"])).replace(
            '%zero_time = "nodal.real_literal"() <{value = 0.0',
            '%zero_time = "nodal.real_literal"() <{value = -1.0'), "040-004")
        for result_type in ['!nodal.quantity<"real", "current">', '!nodal.quantity<"integer", "voltage">', 'i1']:
            negative(fixture(transfer().replace("-> f64", "-> " + result_type)), "040-003")
        negative(fixture(transfer() + transfer(result="other")).replace("TransferTop.other", "TransferTop.filter"), "040-002")
        # Fold assertions must be independently rejected on the operator and ancestors.
        for attribute in ['nodal.folded = true', 'nodal.folded_value = 0.0 : f64', 'nodal.simplified = true']:
            call = transfer().replace('}}> :', f'}}}}> {{{attribute}}} :')
            negative(fixture(call), "NODAL-ANALOG-FOLD-001")
        # Reject forged claims recursively, not just on a direct transfer use.
        # Test incomplete claims as well as a plausible-looking complete record.
        claims = [
            'nodal.folded = true, nodal.folded_value = 0.0 : f64, '
            'nodal.folded_dimension = "voltage", nodal.folded_kind = "real", '
            'nodal.folded_provenance = "increment30"',
            'nodal.folded_value = 0.0 : f64',
            'nodal.folded_kind = "real"',
            'nodal.simplified = true',
            'nodal.simplified_value = 0.0 : f64',
            'nodal.simplification_rule = "invented-zero"',
        ]
        negate = '      %negated = "nodal.analog_neg"(%sum) <{metadata = {}}> : (f64) -> f64\n'
        multiply = '      %scaled = "nodal.analog_mul"(%negated, %zero) <{metadata = {}}> : (f64, f64) -> f64\n'
        absolute = ('      %absolute = "nodal.analog_function"(%scaled) '
                    '<{function_id = "abs", registry_version = "1", metadata = {}}> : (f64) -> f64\n')
        for kind in ["laplace_nd", "zi_nd"]:
            # Valid expressions retain state, including multiplication by zero.
            positive(fixture(transfer(kind) + addition + negate + multiply + absolute + contribution("absolute")))
            for prefix, operation in [("", addition), (addition, negate),
                                      (addition + negate, multiply),
                                      (addition + negate + multiply, absolute)]:
                for claim in claims:
                    forged = operation.replace('}> :', '}> {' + claim + '} :')
                    assert forged != operation
                    negative(fixture(transfer(kind) + prefix + forged), "NODAL-ANALOG-FOLD-001")
        if source:
            text = source.read_text()
            _, va, after = positive(text)
            assert text.count('"nodal.analog_transfer"') == 6
            for output in [va, after]:
                assert output.count("laplace_nd(") == 3 and output.count("zi_nd(") == 3
                assert "(transfer_0 + transfer_0)" in output
                assert "zi_nd(transfer_0," in output
                assert "gain" in output and "tau" in output
        print(f"Increment 40 native/source matrix: {count} cases passed")
        return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nodalc", type=Path, required=True)
    parser.add_argument("--translate", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    run(args.nodalc.resolve(), args.translate.resolve(), args.source)


if __name__ == "__main__":
    main()

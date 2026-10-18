#!/usr/bin/env python3
"""Trace the small scalar subset in actual live-fill backend witnesses.

This is a test interpreter for the emitted lazy expression/effect contract, not
an analog simulator or a claim about monitor scheduling. Feed the artifact
directory written by nodal-writable-array-backend-unit-tests.
"""

import collections
import pathlib
import re
import sys


class ExpressionParser:
    def __init__(self, source):
        self.tokens = re.findall(r"[A-Za-z_][A-Za-z_0-9]*|\d+(?:\.\d+)?|[?:(),+*/-]", source)
        assert "".join(self.tokens) == re.sub(r"\s+", "", source)
        self.position = 0

    def take(self, expected=None):
        token = self.tokens[self.position]
        if expected is not None:
            assert token == expected, (token, expected)
        self.position += 1
        return token

    def peek(self):
        return self.tokens[self.position] if self.position < len(self.tokens) else ""

    def expression(self, minimum=0):
        token = self.take()
        if token == "(":
            value = self.expression()
            self.take(")")
        elif re.fullmatch(r"\d+(?:\.\d+)?", token):
            value = ("literal", float(token))
        else:
            value = ("name", token)
            if self.peek() == "(":
                self.take("(")
                arguments = [self.expression()]
                while self.peek() == ",":
                    self.take(",")
                    arguments.append(self.expression())
                self.take(")")
                value = ("call", token, arguments)
        priorities = {"+": 1, "-": 1, "*": 2, "/": 2}
        while self.peek() in priorities and priorities[self.peek()] >= minimum:
            operation = self.take()
            value = (operation, value, self.expression(priorities[operation] + 1))
        if minimum == 0 and self.peek() == "?":
            self.take("?")
            yes = self.expression()
            self.take(":")
            value = ("select", value, yes, self.expression())
        return value


def parse_events(target):
    lines = [line.strip() for line in target.splitlines() if line.strip().startswith("@(")]
    assert len(lines) == 1
    line = lines[0]
    assert line.endswith(") begin")
    pieces = line[2:-7].split(" or ")
    events = []
    for piece in pieces:
        assert piece.startswith("above(") and piece.endswith(")")
        parser = ExpressionParser(piece[6:-1])
        events.append(parser.expression())
        assert parser.position == len(parser.tokens)
    return events


def trace(path, fill_count, expected, source_reads):
    target = path.read_text()
    events = parse_events(target)
    ready = re.findall(r"integer (event_live_ready_\d+);", target)
    assert len(ready) == fill_count
    for reverse in (False, True):
        state = {}
        # Keep previous caches deliberately. Only actual emitted flag resets
        # delimit visits, and no simulated time/handler invocation is consulted.
        for source in (2.0, 4.0):
            for name in ready:
                assert f"{name} = 0;" in target
                state[name] = 0
            state["event_Arrays_liveSource"] = source
            calls = collections.Counter()
            reads = collections.Counter()

            def evaluate(node):
                operation = node[0]
                if operation == "literal":
                    return node[1]
                if operation == "name":
                    reads[node[1]] += 1
                    assert node[1] in state, ("read before definition", node[1])
                    return state[node[1]]
                if operation == "select":
                    return evaluate(node[2] if evaluate(node[1]) else node[3])
                if operation == "call":
                    assert re.fullmatch(r"event_live_store(?:_\d+)?", node[1])
                    arguments = node[2]
                    assert len(arguments) == 3 and arguments[1][0] == arguments[2][0] == "name"
                    value = evaluate(arguments[0])
                    cache, flag = arguments[1][1], arguments[2][1]
                    assert cache.removeprefix("event_live_cache_") == flag.removeprefix("event_live_ready_")
                    calls[cache] += 1
                    state[cache] = value
                    state[flag] = 1
                    return value
                if reverse:
                    right, left = evaluate(node[2]), evaluate(node[1])
                else:
                    left, right = evaluate(node[1]), evaluate(node[2])
                if operation == "+":
                    return left + right
                if operation == "-":
                    return left - right
                if operation == "*":
                    return left * right
                assert operation == "/" and right != 0
                return left / right

            values = [None] * len(events)
            order = reversed(range(len(events))) if reverse else range(len(events))
            for index in order:
                values[index] = evaluate(events[index])
            assert values == expected(source), (path, reverse, source, values)
            assert len(calls) == fill_count and set(calls.values()) == {1}, calls
            assert reads["event_Arrays_liveSource"] == source_reads, reads
    print(f"{path.name}: both OR/operand orders; once-only fills; skipped demand; fresh visits")


def main():
    directory = pathlib.Path(sys.argv[1])
    for extension in ("va", "vams"):
        trace(directory / f"live-fill-diamond.{extension}", 8, lambda source: [7.0, 128 / source], 1)
    trace(directory / "live-fill-distinct.va", 2, lambda source: [source * 2], 2)
    print("live inline trace checks passed")


if __name__ == "__main__":
    main()

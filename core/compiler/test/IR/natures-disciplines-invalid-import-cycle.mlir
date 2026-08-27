module {
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "pkg://a", sym_name = "A", target = @B}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {}, source = "pkg://b", sym_name = "B", target = @A}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidImport"}> ({ ^bb0: }) : () -> ()
}

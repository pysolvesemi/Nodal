if(NOT DEFINED NODALC)
  message(FATAL_ERROR "NODALC is required")
endif()
if(NOT DEFINED FIXTURE)
  message(FATAL_ERROR "FIXTURE is required")
endif()
if(NOT DEFINED DIAGNOSTIC)
  message(FATAL_ERROR "DIAGNOSTIC is required")
endif()

execute_process(
  COMMAND "${NODALC}" "${FIXTURE}"
  RESULT_VARIABLE result
  OUTPUT_VARIABLE standard_output
  ERROR_VARIABLE standard_error
)

set(output "${standard_output}${standard_error}")
message("${output}")

if(result EQUAL 0)
  message(FATAL_ERROR "expected nodalc to reject ${FIXTURE}")
endif()

string(FIND "${output}" "${DIAGNOSTIC}" diagnostic_position)
if(diagnostic_position EQUAL -1)
  message(FATAL_ERROR "expected diagnostic ${DIAGNOSTIC}")
endif()

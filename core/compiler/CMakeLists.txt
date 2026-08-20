add_subdirectory(lib)
add_subdirectory(tools)

if(NODAL_ENABLE_TESTS)
  add_subdirectory(test)
endif()

include_directories("${CMAKE_CURRENT_BINARY_DIR}/include")

add_subdirectory(include)
add_subdirectory(lib)
add_subdirectory(tools)

if(NODAL_ENABLE_TESTS)
  add_subdirectory(test)
endif()

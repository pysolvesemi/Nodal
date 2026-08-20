cmake_minimum_required(VERSION 3.20.0)

project(Nodal
  VERSION 0.0.0
  DESCRIPTION "Scala 3 and MLIR/CIRCT hardware construction language for AMS modeling"
  LANGUAGES C CXX
)

set(CMAKE_BUILD_WITH_INSTALL_NAME_DIR ON)
set(CMAKE_CXX_STANDARD 17 CACHE STRING "C++ standard used by the Nodal native compiler")
set(CMAKE_CXX_STANDARD_REQUIRED YES)
set(CMAKE_CXX_EXTENSIONS NO)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(NODAL_ENABLE_TESTS "Build and register Nodal native compiler tests" ON)

set(NODAL_REPOSITORY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
include(cmake/NodalToolchain.cmake)

set(LLVM_RUNTIME_OUTPUT_INTDIR "${CMAKE_BINARY_DIR}/bin")
set(LLVM_LIBRARY_OUTPUT_INTDIR "${CMAKE_BINARY_DIR}/lib")
set(MLIR_BINARY_DIR "${CMAKE_BINARY_DIR}")

list(APPEND CMAKE_MODULE_PATH "${CIRCT_CMAKE_DIR}")
list(APPEND CMAKE_MODULE_PATH "${MLIR_CMAKE_DIR}")
list(APPEND CMAKE_MODULE_PATH "${LLVM_CMAKE_DIR}")

include(TableGen)
include(AddLLVM)
include(AddMLIR)
include(AddCIRCT)
include(HandleLLVMOptions)

# LLVM helper modules may repopulate LLVM_DEFINITIONS after package discovery.
# Normalize again immediately before applying them to Nodal's directory scope.
nodal_normalize_llvm_definitions()

include_directories(${LLVM_INCLUDE_DIRS})
include_directories(${MLIR_INCLUDE_DIRS})
include_directories(${CIRCT_INCLUDE_DIRS})
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/core/compiler/include")
include_directories("${CMAKE_BINARY_DIR}/include")
link_directories(${LLVM_BUILD_LIBRARY_DIR})
link_directories(${CIRCT_LIBRARY_DIRS})
add_definitions(${LLVM_DEFINITIONS})

file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/include/nodal/Support")
configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/cmake/NodalVersion.inc.in"
  "${CMAKE_BINARY_DIR}/include/nodal/Support/NodalVersion.inc"
  @ONLY
)

if(NODAL_ENABLE_TESTS)
  include(CTest)
  enable_testing()
endif()

add_subdirectory(core/compiler)

# Resolve and verify the managed LLVM/MLIR/CIRCT installation selected by
# toolchains/lock.json. This file executes in the including directory scope.

function(_nodal_json_get output json_text)
  string(JSON value ERROR_VARIABLE error GET "${json_text}" ${ARGN})
  if(error)
    message(FATAL_ERROR "Cannot read ${ARGN} from Nodal toolchain JSON: ${error}")
  endif()
  set(${output} "${value}" PARENT_SCOPE)
endfunction()

file(READ "${NODAL_REPOSITORY_ROOT}/toolchains/lock.json" NODAL_TOOLCHAIN_LOCK_JSON)
_nodal_json_get(NODAL_NATIVE_LOCK_ID "${NODAL_TOOLCHAIN_LOCK_JSON}" lock_id)
_nodal_json_get(NODAL_CIRCT_RELEASE_TAG "${NODAL_TOOLCHAIN_LOCK_JSON}" native circt release_tag)
_nodal_json_get(NODAL_CIRCT_COMMIT "${NODAL_TOOLCHAIN_LOCK_JSON}" native circt commit)
_nodal_json_get(NODAL_LLVM_COMMIT "${NODAL_TOOLCHAIN_LOCK_JSON}" native llvm commit)
_nodal_json_get(NODAL_LLVM_PACKAGE_VERSION "${NODAL_TOOLCHAIN_LOCK_JSON}" native llvm package_version)
_nodal_json_get(NODAL_CXX_STANDARD "${NODAL_TOOLCHAIN_LOCK_JSON}" native requirements cxx_standard)

if(NOT NODAL_NATIVE_TOOLCHAIN AND DEFINED ENV{NODAL_NATIVE_TOOLCHAIN})
  set(NODAL_NATIVE_TOOLCHAIN "$ENV{NODAL_NATIVE_TOOLCHAIN}" CACHE PATH
      "Managed LLVM/MLIR/CIRCT installation selected by Nodal")
endif()

if(NOT NODAL_NATIVE_TOOLCHAIN)
  message(FATAL_ERROR
    "NODAL_NATIVE_TOOLCHAIN is required. Install or discover the locked package with "
    "'python3 scripts/bootstrap_native_toolchain.py install --mode auto', then pass "
    "'-DNODAL_NATIVE_TOOLCHAIN=<prefix>' or export NODAL_NATIVE_TOOLCHAIN.")
endif()

get_filename_component(NODAL_NATIVE_TOOLCHAIN "${NODAL_NATIVE_TOOLCHAIN}" REALPATH)
set(NODAL_NATIVE_TOOLCHAIN "${NODAL_NATIVE_TOOLCHAIN}" CACHE PATH
    "Managed LLVM/MLIR/CIRCT installation selected by Nodal" FORCE)

set(_nodal_manifest_path "${NODAL_NATIVE_TOOLCHAIN}/.nodal-toolchain.json")
if(NOT EXISTS "${_nodal_manifest_path}")
  message(FATAL_ERROR
    "${NODAL_NATIVE_TOOLCHAIN} is not a managed Nodal toolchain: "
    "${_nodal_manifest_path} is missing")
endif()

file(READ "${_nodal_manifest_path}" NODAL_TOOLCHAIN_MANIFEST_JSON)
_nodal_json_get(_manifest_lock_id "${NODAL_TOOLCHAIN_MANIFEST_JSON}" lock_id)
_nodal_json_get(_manifest_circt_tag "${NODAL_TOOLCHAIN_MANIFEST_JSON}" circt_release_tag)
_nodal_json_get(_manifest_circt_commit "${NODAL_TOOLCHAIN_MANIFEST_JSON}" circt_commit)
_nodal_json_get(_manifest_llvm_commit "${NODAL_TOOLCHAIN_MANIFEST_JSON}" llvm_commit)

foreach(_field IN ITEMS lock_id circt_tag circt_commit llvm_commit)
  if(_field STREQUAL "lock_id")
    set(_actual "${_manifest_lock_id}")
    set(_expected "${NODAL_NATIVE_LOCK_ID}")
  elseif(_field STREQUAL "circt_tag")
    set(_actual "${_manifest_circt_tag}")
    set(_expected "${NODAL_CIRCT_RELEASE_TAG}")
  elseif(_field STREQUAL "circt_commit")
    set(_actual "${_manifest_circt_commit}")
    set(_expected "${NODAL_CIRCT_COMMIT}")
  else()
    set(_actual "${_manifest_llvm_commit}")
    set(_expected "${NODAL_LLVM_COMMIT}")
  endif()
  if(NOT _actual STREQUAL _expected)
    message(FATAL_ERROR
      "Managed toolchain ${_field} is '${_actual}', expected '${_expected}'")
  endif()
endforeach()

if(NOT CMAKE_CXX_STANDARD EQUAL NODAL_CXX_STANDARD)
  message(FATAL_ERROR
    "Nodal requires C++${NODAL_CXX_STANDARD}; configured C++${CMAKE_CXX_STANDARD}")
endif()

foreach(_required IN ITEMS
    "bin/circt-opt${CMAKE_EXECUTABLE_SUFFIX}"
    "bin/mlir-opt${CMAKE_EXECUTABLE_SUFFIX}"
    "lib/cmake/circt/CIRCTConfig.cmake"
    "lib/cmake/mlir/MLIRConfig.cmake"
    "lib/cmake/llvm/LLVMConfig.cmake")
  if(NOT EXISTS "${NODAL_NATIVE_TOOLCHAIN}/${_required}")
    message(FATAL_ERROR
      "Managed Nodal toolchain is incomplete: ${_required} is missing")
  endif()
endforeach()

list(PREPEND CMAKE_PREFIX_PATH "${NODAL_NATIVE_TOOLCHAIN}")
set(CIRCT_DIR "${NODAL_NATIVE_TOOLCHAIN}/lib/cmake/circt" CACHE PATH "" FORCE)
set(MLIR_DIR "${NODAL_NATIVE_TOOLCHAIN}/lib/cmake/mlir" CACHE PATH "" FORCE)
set(LLVM_DIR "${NODAL_NATIVE_TOOLCHAIN}/lib/cmake/llvm" CACHE PATH "" FORCE)

find_package(CIRCT REQUIRED CONFIG)

set(CMAKE_BUILD_RPATH "${NODAL_NATIVE_TOOLCHAIN}/lib")
set(CMAKE_INSTALL_RPATH "${NODAL_NATIVE_TOOLCHAIN}/lib")
set(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)

message(STATUS "Nodal native lock: ${NODAL_NATIVE_LOCK_ID}")
message(STATUS "Nodal CIRCT release: ${NODAL_CIRCT_RELEASE_TAG}")
message(STATUS "Using CIRCTConfig.cmake in: ${CIRCT_DIR}")
message(STATUS "Using MLIRConfig.cmake in: ${MLIR_DIR}")
message(STATUS "Using LLVMConfig.cmake in: ${LLVM_DIR}")

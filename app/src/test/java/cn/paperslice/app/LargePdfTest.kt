package cn.paperslice.app

// Real large-file regression tests moved to tools/test_native.py.
// They exercise the exact C++ core with 300 MB and 1 GB shared-resource PDFs
// under a 256 MiB address-space limit and compare every selected page's pixels.

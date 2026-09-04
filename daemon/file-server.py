#!/usr/bin/env python3
"""Legacy shim — delegates to file_server.py (underscore) which is the canonical module."""
import file_server
if __name__ == "__main__":
    file_server.main()

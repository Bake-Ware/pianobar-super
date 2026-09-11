#!/usr/bin/env python3
"""Prompt for install credentials or validate a private JSON automation file."""
import argparse
import getpass
import json
import os
from pathlib import Path
import stat
import sys


def credentials(path=None):
    if path:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd) as stream:
            mode = os.fstat(stream.fileno())
            if not stat.S_ISREG(mode.st_mode) or mode.st_mode & 0o077:
                raise ValueError('Credential file must be a regular file with mode 0600 or stricter.')
            data = json.load(stream)
    else:
        with open('/dev/tty', 'r') as reader, open('/dev/tty', 'w') as terminal:
            terminal.write('Pandora email: ')
            terminal.flush()
            user = reader.readline().rstrip('\n')
            password = getpass.getpass('Pandora password: ', stream=terminal)
            web_password = getpass.getpass('Web login password (username: pianobar): ', stream=terminal)
            if web_password != getpass.getpass('Confirm web login password: ', stream=terminal):
                raise ValueError('Web passwords do not match.')
        data = dict(user=user, password=password, web_password=web_password)
    if not isinstance(data, dict) or set(data) != {'user', 'password', 'web_password'}:
        raise ValueError('Expected user, password and web_password fields only.')
    for key, value in data.items():
        if not isinstance(value, str) or not value or len(value.encode()) > 450 or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError(f'{key} must be a nonempty single line of at most 450 bytes.')
        if value != value.strip():
            raise ValueError(f'{key} cannot start or end with whitespace.')
    if len(data['web_password']) < 12:
        raise ValueError('Use at least 12 characters for the web login password.')
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        data = credentials(args.input)
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream)
    except (OSError, ValueError) as error:
        # JSON decoder errors may contain input; never echo supplied credentials.
        print('Credential setup failed. Check the file format, permissions and input requirements.'
              if args.input else f'Credential setup failed: {error}', file=sys.stderr)
        sys.exit(1)

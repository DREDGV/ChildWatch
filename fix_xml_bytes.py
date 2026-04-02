# -*- coding: utf-8 -*-
import re

file_path = r'parentwatch\src\main\res\layout\activity_settings.xml'

with open(file_path, 'rb') as f:
    content = f.read()

# Replace corrupted bytes directly
content = content.replace(
    b'\xd1\x80\xd0\xb5\xd1\x81\xd0\xbe\xd1\x85\xd1\x80\xd0\xb0\xd0\xbd\xd0\xb8\xd1\x82\xd1\x8c',
    b'\xd0\xa1\xd0\xbe\xd1\x85\xd1\x80\xd0\xb0\xd0\xbd\xd0\xb8\xd1\x82\xd1\x8c'
)

with open(file_path, 'wb') as f:
    f.write(content)

print('Fixed!')

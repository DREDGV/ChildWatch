# -*- coding: utf-8 -*-
import re

file_path = r'parentwatch\src\main\res\layout\activity_settings.xml'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace corrupted text
content = content.replace('рџ'Ѕ РЎРѕС…СЂР°РЅРёС‚СЊ РЅР°СЃС‚СЂРѕР№РєРё', '💾 Сохранить настройки')
content = content.replace('в„№пёЏ РР·РјРµРЅРµРЅРёСЏ РІСЃС‚СѓРїСЏС‚ РІ СЃРёР»Сѓ РїРѕСЃР»Рµ РїРµСЂРµР·Р°РїСѓСЃРєР° РјРѕРЅРёС‚РѕСЂРёРЅРіР°.', 'ℹ️ Изменения вступят в силу после перезапуска мониторинга.')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print('Fixed!')

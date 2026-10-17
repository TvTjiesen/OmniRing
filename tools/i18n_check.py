#!/usr/bin/env python3
"""比对四份 strings.xml（简中 / 繁中 / English / Русский）的键名与占位符。

多语言最容易漏的不是翻译而是：新加键只加了中文、占位符数量对不上、
或者 XML 转义写错。这些都能机器查，所以放在构建前跑。
用法：python3 tools/i18n_check.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res')
LANGS = ['values', 'values-b+zh+Hant', 'values-en', 'values-ru']


def load(folder):
    path = os.path.join(RES, folder, 'strings.xml')
    if not os.path.exists(path):
        return None
    out = {}
    for e in ET.parse(path).getroot():
        if e.tag == 'string':
            out[e.get('name')] = ''.join(e.itertext())
    return out


def placeholders(text):
    return sorted(set(re.findall(r'%(\d+)\$|%(?=[sd])', text)))


def main():
    tables = {lang: load(lang) for lang in LANGS}
    base = tables['values']
    if base is None:
        print('values/strings.xml 不存在')
        return 1
    bad = 0
    print(f'values={len(base)}', end='')
    for lang in LANGS[1:]:
        t = tables[lang]
        print(f' {lang}={0 if t is None else len(t)}', end='')
    print()

    for lang in LANGS[1:]:
        t = tables[lang]
        if t is None:
            print(f'!! {lang} 整个缺失')
            bad += 1
            continue
        for k in sorted(set(base) - set(t)):
            print(f'!! {lang} 缺键: {k}')
            bad += 1
        for k in sorted(set(t) - set(base)):
            print(f'!! {lang} 多余键: {k}')
            bad += 1
        for k in sorted(set(base) & set(t)):
            a, b = placeholders(base[k]), placeholders(t[k])
            if a != b:
                print(f'!! {lang} 占位符不一致: {k} 中文{a} vs {b}')
                bad += 1
    print('OK' if bad == 0 else f'{bad} 处问题')
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main())

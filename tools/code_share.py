import os, collections
os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'kotlin', 'xyz', 'nextalone', 'hyperzoomring'))
NEW = set("""ring/CrownModel.kt ring/ScrollTracker.kt ring/ScrubModel.kt ring/ScrubHoldModel.kt ring/SeekGate.kt
hook/SensorRingDriver.kt hook/DebugProbe.kt hook/ZoomRingSensorListener.kt action/actions/ScrollAction.kt
action/actions/MediaScrubAction.kt action/actions/ScrubDragAction.kt hud/CrownHud.kt media/MediaSessionBridge.kt
ui/GlassBar.kt ui/AppTheme.kt ui/Labels.kt ui/screen/Page.kt ui/screen/SettingsScreen.kt util/ScopeReload.kt
ui/liquid/SmearMath.kt""".split())
MOD = set("""action/ActionRegistry.kt config/ConfigManager.kt hook/InputInterceptorHook.kt hook/SceneDetector.kt
ui/MainActivity.kt HookEntry.kt action/actions/MediaAction.kt""".split())
tot, lines = collections.Counter(), collections.Counter()
for root, _, fs in os.walk('.'):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        p = os.path.relpath(os.path.join(root, fn), '.').replace(os.sep, '/')
        n = sum(1 for _ in open(os.path.join(root, fn), encoding='utf-8'))
        if p.startswith('ui/liquid'):
            cat = '移植：Miuix示例(Apache)'
        elif p in NEW:
            cat = '二改新增'
        elif p in MOD:
            cat = '二改修改(上游文件)'
        else:
            cat = '上游原样'
        tot[cat] += 1
        lines[cat] += n
L = sum(lines.values()); F = sum(tot.values())
print(f'合计 {F} 个 .kt / {L} 行')
for k in ['上游原样', '二改修改(上游文件)', '二改新增', '移植：Miuix示例(Apache)']:
    print(f'  {k:24s} {tot[k]:3d} 文件 {lines[k]:5d} 行 {lines[k]*100//L:3d}%')
# 上游原样的文件清单（判断"弱化"的边界）
print('\n上游原样文件：')
for root, _, fs in os.walk('.'):
    for fn in fs:
        if fn.endswith('.kt'):
            p = os.path.relpath(os.path.join(root, fn), '.').replace(os.sep, '/')
            if not p.startswith('ui/liquid') and p not in NEW and p not in MOD:
                print('   ', p, sum(1 for _ in open(os.path.join(root, fn), encoding='utf-8')))

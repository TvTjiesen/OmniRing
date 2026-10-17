import re, statistics, sys
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else r'D:\dev\hzr\diag.log'
rx = re.compile(r'^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HyperZoomRing: SMP pos=(-?[\d.]+) tgt=(-?[\d.]+) v=(-?[\d.]+) peak=(-?[\d.]+) dir=(-?\d+) acc=(yes|no)')

rows = []
for line in open(path, encoding='utf-8', errors='ignore'):
    m = rx.match(line)
    if not m:
        continue
    t = float(m.group(1))
    rows.append(dict(t=t, pos=float(m.group(2)), tgt=float(m.group(3)), v=float(m.group(4)),
                     peak=float(m.group(5)), dir=int(m.group(6)), acc=m.group(7) == 'yes'))

print(f'解析到 SMP 样本 {len(rows)} 条')
if len(rows) < 10:
    sys.exit('样本太少')

t0 = rows[0]['t']
for r in rows:
    r['ms'] = (r['t'] - t0) * 1000.0

# 相邻样本的位置差
deltas = []
for i in range(1, len(rows)):
    d = rows[i]['pos'] - rows[i-1]['pos']
    dt = rows[i]['ms'] - rows[i-1]['ms']
    deltas.append((d, dt, rows[i]['acc']))

pos_d = [d for d, _, _ in deltas if d > 0]
neg_d = [d for d, _, _ in deltas if d < 0]

def stats(name, arr):
    if not arr:
        print(f'{name}: 无样本')
        return
    a = [abs(x) for x in arr]
    a.sort()
    q = lambda p: a[min(len(a)-1, int(len(a)*p))]
    print(f'{name}: n={len(a)} 中位={statistics.median(a):.1f} p10={q(0.1):.1f} p90={q(0.9):.1f} '
          f'最大={max(a):.1f} 低于死区6的比例={sum(1 for x in a if x < 6)/len(a):.0%}')

stats('正向(位置增大)样本步长', pos_d)
stats('负向(位置减小)样本步长', neg_d)

# 采样间隔
gaps = sorted(dt for _, dt, _ in deltas)
print(f'采样间隔: 中位={statistics.median(gaps):.1f}ms p90={gaps[int(len(gaps)*0.9)]:.1f}ms 最大={max(gaps):.1f}ms')

# 采纳情况
acc = [r for r in rows if r['acc']]
rej = [r for r in rows if not r['acc']]
print(f'采纳 {len(acc)} / 拒绝 {len(rej)}')

# 按方向统计目标推进量：把连续同向的段切出来
seg = []
cur_sign = 0
cur = None
for i in range(1, len(rows)):
    d = rows[i]['pos'] - rows[i-1]['pos']
    s = 1 if d > 0 else (-1 if d < 0 else 0)
    if s == 0:
        continue
    if s != cur_sign:
        if cur:
            seg.append(cur)
        cur = dict(sign=s, raw=0.0, tgt0=rows[i-1]['tgt'], t0=rows[i-1]['ms'], n=0, rej=0)
        cur_sign = s
    if cur:
        cur['raw'] += d
        cur['n'] += 1
        if not rows[i]['acc']:
            cur['rej'] += 1
if cur:
    seg.append(cur)
for s in seg:
    s['tgt_delta'] = None
# 目标推进量：用每段首尾 tgt 差
for i, s in enumerate(seg):
    end_t = seg[i+1]['tgt0'] if i+1 < len(seg) else rows[-1]['tgt']
    s['tgt_delta'] = (end_t - s['tgt0']) * s['sign']   # 正=该方向确实推进了

print('\n按转向分段（raw=传感器累计位移单位, tgt=目标实际推进单位, 采纳/总样本）')
for s in seg:
    if abs(s['raw']) < 30:
        continue
    print(f"  {'顺' if s['sign']>0 else '逆'} raw={s['raw']:+8.0f} tgt推进={s['tgt_delta']:+8.0f} "
          f"样本={s['n']:3d} 被吞={s['rej']:3d} 时长={s['t0'] and ''}{''}")

tot_pos = sum(s['raw'] for s in seg if s['sign'] > 0)
tot_neg = sum(s['raw'] for s in seg if s['sign'] < 0)
print(f'\n合计：正向 raw={tot_pos:.0f} 负向 raw={tot_neg:.0f}')
tp = sum(s['tgt_delta'] for s in seg if s['sign'] > 0)
tn = -sum(s['tgt_delta'] for s in seg if s['sign'] < 0)
print(f'合计：正向目标推进={tp:.0f} 负向目标推进={tn:.0f}  （两边都该≈各自 raw 减去回差）')

# 时间跨度
print(f'总时长 {rows[-1]["ms"]-rows[0]["ms"]:.0f}ms')

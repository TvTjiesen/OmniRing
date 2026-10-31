# OmniRing · HyperOS 3 测试回执（给酷友的，直接复制发出去就行）

你手上的 **小米 17 Ultra（含徕卡版）如果还停在澎湃 OS 3（Android 16）**，麻烦帮我测一轮。
这个版本在 OS4 上已经调好了，OS3 没有机器可测，所以需要你这几分钟。**只需要拍照/滚动几下 + 截两张图。**

先装：**这一轮请装 `OmniRing-1.39.0-beta-legacy.apk`**（文件名里带 legacy 的那一份）。
原因说清楚：包里原本同时声明了两条模块入口，新一点的 LSPosed（2.2.x）认这个组合，
但**老版本 LSPosed 撞上双声明会一条都不加载** —— 模块在管理器里显示"已启用"，实际什么都不干。
我手上没有 OS3 的机器，也不知道你手机上那份 LSPosed 是哪版，所以给你的这份只留老的那条入口，
新老框架都能加载（代价是 LSPosed 里会重新出现"此模块使用了已废弃的功能"那条黄字，**不用管它**，
功能不受影响）。

安装步骤：安装 → LSPosed 里勾选作用域「系统框架」并把模块本身打开 → **打开一次 LSPosed 管理器**
（不打开它不会重新扫描，模块会装作加载了其实没有）→ 重启手机 → **打开一次 OmniRing**
（配置是 App 推给系统侧的，不开一次的话首页卡上会写"没收到配置"）。

装完先自查一眼是不是真的在跑（不用电脑也行）：首页最上面那张卡应该是绿的，
并且**下面那个「启用变焦环」的开关是打开的** —— 开关关着的时候环一定不动，那不是 bug。

---

## 一、必做（不需要电脑）

1. 打开 OmniRing，首页最上面那张卡（「本机与适配」）。**截图整张卡**。
   我要看的是这三行：`系统`（应该显示 HyperOS 3.x）、`技术路径`（写着"传感器通道"还是"两条通道并存"）、`环数据`（数字有没有在涨）。
2. 在**桌面**上转环，看看列表会不会滚。
3. 打开**浏览器/微博任意一个长页面**转环，看滚不滚、**一次转动是不是滚了两下**（这是这轮最需要你判断的一点）。
4. 打开**相机**，转环。期望：**只有原生变焦，不应该出现"上下滑动"的动画**。这条多试几次（他这里之前有概率出问题）。
5. 打开**设置页**里那颗右上角的重启按钮，看有没有出现"转一下就当成点了一下"的误触。

回答这四个问题（一句话就行）：
- A. 一次转环，列表有没有"走两下"？（有 / 没有）
- B. 相机里转环，有没有出现上滑/下滑的画面？（有 / 没有，有就说是哪个模式）
- C. 应用内（OmniRing 自己）转环跟不跟手？（跟手 / 顿 / 完全不动）
- D. 音量、亮度这两类动作，一次转动有没有触发两次？（有 / 没有）

## 二、有电脑的话，多给我这几行（价值最大）

连上电脑，依次执行，把输出整段发回来：

```
adb shell settings put global hzr_debug 1
adb shell settings put global hzr_probe 8192
adb shell sleep 5
adb shell settings get global hzr_entry
adb shell settings get global hzr_heartbeat
adb logcat -d -s HyperZoomRing > hzr.txt
```

`hzr_probe 8192` 那两行是**必须**的：它逼模块自己回一句"我现在到底加载的哪一版、走的哪条入口、
装了哪几个钩点"。光看心跳签名分不出来 —— 签名只是那个 apk 文件的大小和时间，
文件是新的、system_server 里跑的却可能是旧的。期望看到这一行（`build=` 后面是版本号）：

```
entry=legacy installed=0  legacyHooked=filterInputEvent,injectInputEvent,launchCamera
runtime=started=true ctx=ok cfg=ok recv=ok …  build=cfg42
```

看到 `entry=legacy` 就对了（这份包只留老入口）。`installed=0` **不是**故障，那是另一条入口的计数器；
真正代表装钩子的是 `legacyHooked=`。这一行整段发我，比任何截图都有用。

然后**去相机里转环 10 次**、**去长页面转环 10 次**，再执行一次：

```
adb shell settings get global hzr_heartbeat
adb logcat -d -s HyperZoomRing > hzr2.txt
```

把 `hzr.txt`、`hzr2.txt`、`hzr_entry` 和两次 `hzr_heartbeat` 的输出一起发我。

**我判读什么（你不用管，写在这里免得你以为我没看）**：
- 心跳最后一形如 `执行数/只消费数`。`0/5` 这种 = 输入侧收到事件了但被挡住了（OS3 正常态，说明不会双倍执行）；`0/0` = 输入侧一个事件都没收到（和 OS4 一样）；`3/0` = **仲裁没挡住，真在双倍执行**，这条我最怕看到。
- 日志里的 `FG 执行注入 scroll fg=... cam=false 切换后 120ms` —— 有这条就说明相机那次误触发的原因是"读到空/读慢一拍"还是"包名不在白名单"，一眼能分。

## 三、想快点试手感（可选，都不用自己装）

```
adb shell settings put global hzr_ring_lease_ms 0      # 关掉双入口互斥（怀疑我挡错方向时试）
adb shell settings put global hzr_ring_lease_ms 700    # 恢复默认
adb shell settings put global hzr_fg_stable_ms 0        # 关掉"刚换应用不注手指"（相机那条闸门）
adb shell settings put global hzr_fg_stable_ms 600      # 恢复默认
adb shell settings put global hzr_ring_px_per_unit 2.0  # 默认已是 2.5（真机调出来的），嫌多就往下调
adb shell settings delete global hzr_ring_px_per_unit   # 删掉键就回到代码默认 2.5
adb shell settings put global hzr_debug 0               # 测完关掉日志
```

## 四、不用测的

- 不要为了测试刷系统 / 降级 / 解锁 BL。这台机器的变焦环是物理件，刷坏了没法救。
- 不要开 `overrideCamera`（相机覆盖）那个开关，除非你就是想试原生变焦被抢走会是什么样。
- 别把设备号、微信/相册截图发群里，日志里如果有账号信息先自己删。

---

回执模板（照抄就行）：

```
机型：17 Ultra 徕卡版 / 17 Ultra
系统：HyperOS ____（Android 16）
模块版本：1.35.0-beta（cfg39）
A 双倍执行：有 / 没有
B 相机误触发：有 / 没有，模式____
C 应用内跟手：跟手 / 顿 / 不动
D 音量亮度双触发：有 / 没有
附件：适配卡截图 +（有电脑的话）hzr.txt / hzr2.txt + 两次 heartbeat 输出
```

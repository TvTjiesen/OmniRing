# HyperOS 3 测试：怎么发给酷友

业主定的做法：**只发 apk，不教 adb**；测试者遇到问题直接口头找他，没问题就上架。
所以这份文档**只有第一节是要转发的**，后面都是我们自己看的。

---

## 一、直接复制这段发给测试者

> 给你一个能改变焦环功能的小模块（要 root + LSPosed）。你如果用的是小米 17 Ultra / 17 Ultra 徕卡版，系统还停在澎湃 OS 3（Android 16），帮我试 5 分钟。
>
> 装法：装 apk → LSPosed 里把这个模块打开、作用域勾「系统框架 / System Framework」→ **打开一次 LSPosed 管理器**（不开它不会重新扫描，模块会看着像装了其实没生效）→ 重启手机 → 打开一次这个 App。
>
> 用完告诉我四句话就行，不用截图、不用电脑：
>
> 1. 桌面、浏览器、微博这类长页面里转环，滚不滚？距离正常吗，**会不会一下滚过头**？
> 2. 相机里转环，还是原来的变焦吗？有没有多出"上下滑动"的画面？
> 3. 音量、亮度这类动作，转一下会不会**跳两格**（一下变双倍）？
> 4. 在 App 自己页面里转环，跟不跟手？
>
> 顺手看一眼 App 首页最上面那张卡：写着"已生效"就是好了；写着"未加载 / 待重启 / 总开关是关的"，直接把那句原话告诉我就行，我能判断原因。
>
> 两个小提醒：LSPosed 里可能出现一行"此模块使用了已废弃的功能"的黄字，**不用管**，功能不受影响；如果之前装过我给的测试版，这份签名不一样，要**先卸载旧的**再装。

## 二、他口述时你怎么问（就这四条，别多问）

- 听到「一下滚过头 / 跳两格」→ 这是我最怕的**双倍执行**：OS3 上系统输入侧和模块传感器侧会各收到一次转动。新包的仲裁器专门挡这个，所以真出现就是要修的 bug，请他补一句"哪个页面、转多快"。
- 听到「完全不动」→ 九成是这两种：① LSPosed 没重新扫描（让他打开一次管理器再重启）；② App 首页那个「启用变焦环」总开关是关的（卡上会直接写）。
- 听到「装不上 / 已安装相同版本」→ 他机器上还留着旧签名那份，卸载旧的再装。
- 听到「相机里还是乱滑」→ 让他说是系统相机还是第三方、什么模式。

## 三、别让他们做这些事

- **不要为了测试刷系统、降级、解锁 BL**。变焦环是物理件，刷坏了没法救。
- 不要动 `override_camera`（相机覆盖）那个开关，除非他就是想看原生变焦被抢走是什么样。
- 日志、截图里带账号信息的话让他自己删；不要把设备号发群里。

## 四、我这边怎么判（不发给测试者）

### 一张首页截图就够判双倍执行

那张卡的「技术路径」一行现在直接写着路径 + 计数（cfg44 起）：

```
sensor                      ← 输入侧一个事件都没收到（和 OS4 一样）
input+sensor 挡N/执0         ← 输入侧收到事件但全被挡下 → 只执行一遍，OS3 的正常态
input+sensor 挡N/执M（M>0）   ← 有 M 次真是输入侧执行的 → 那一下传感器路在断，要问他有没有"走两下"
```

判据是纯函数，有单测钉住（`HeartbeatParseTest`），不会随界面改版悄悄变味。

### 只有他愿意连电脑时才用这几行

```
adb shell settings put global hzr_debug 1
adb shell settings put global hzr_probe 8192
adb shell settings get global hzr_entry
adb shell settings get global hzr_heartbeat
```

`hzr_probe 8192` 那一条是必须的：它逼模块自己回一句"我加载的哪一版、走哪条入口、装了哪几个钩点"。
光看心跳签名分不出来 —— 那只是文件大小和时间，文件是新的、system_server 里跑的可能是旧的。
期望形状（`installed=0` **不是**故障，那是另一条入口的计数器，真正的账在 `legacyHooked=`）：

```
entry=legacy installed=0 legacyHooked=filterInputEvent,injectInputEvent,launchCamera
runtime=started=true ctx=ok cfg=ok recv=ok …  build=cfg44
```

- 心跳最后一形如 `执行数/只消费数`：`0/5` = 输入侧活着且被挡住；`0/0` = 这台 ROM 收不到环的输入事件；`3/0` = 仲裁没挡住，真在双倍执行（我最怕看到的一行）。
- 日志里 `FG 执行注入 scroll fg=… cam=false 切换后 120ms` 能一眼分清相机那次是"读到空 / 读慢一拍"还是"包名不在白名单"。

### 手感热调（不用重装，A/B 完回默认）

```
adb shell settings put global hzr_ring_lease_ms 0      # 关掉双入口互斥（怀疑我挡错方向时试）
adb shell settings put global hzr_ring_lease_ms 700    # 恢复默认
adb shell settings put global hzr_fg_stable_ms 0       # 关掉"刚换应用不注手指"（相机那条闸门）
adb shell settings put global hzr_fg_stable_ms 600     # 恢复默认
adb shell settings put global hzr_ring_px_per_unit 2.0 # 代码默认已烘成 2.5（真机调出来的），嫌多就往下调
adb shell settings delete global hzr_ring_px_per_unit  # 删掉键 = 回默认 2.5
adb shell settings put global hzr_debug 0             # 测完关掉日志
```

### 要是哪位肯给书面回执，让他照这个贴

```
机型：17 Ultra 徕卡版 / 17 Ultra
系统：HyperOS ____（Android 16）
模块版本：1.40.0-beta（cfg44）
A 是否滚过头：有 / 没有
B 相机误触发：有 / 没有，模式____
C 应用内跟手：跟手 / 顿 / 不动
D 音量亮度双触发：有 / 没有
附件：首页那张卡截图（愿意连电脑的话再加 hzr_entry、hzr_heartbeat 两行输出）
```

package com.example.touchengine // 如果你的包名不一样，记得改这里

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainAppScreen()
            }
        }
    }
}

val BackgroundGray = Color(0xFFF5F6F9)
val TextDarkGray = Color(0xFF555555)

private fun isTouchEngineAccessibilityEnabled(context: Context): Boolean {
    val expectedServiceName = ComponentName(
        context,
        TouchEngineService::class.java
    ).flattenToString()

    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)

    while (splitter.hasNext()) {
        val enabledService = splitter.next()
        if (enabledService.equals(expectedServiceName, ignoreCase = true)) {
            return true
        }
    }

    return false
}

@Composable
fun MainAppScreen() {
    val context = LocalContext.current

    // ================= 核心：读取本地高级设置 =================
    val sharedPrefs = context.getSharedPreferences("TouchEnginePrefs", Context.MODE_PRIVATE)
    var isAutoClick by remember {
        mutableStateOf(sharedPrefs.getBoolean("auto_click", false))
    }

    // 自动滚动配置，从 SharedPreferences 读取
    var scrollConfig by remember {
        mutableStateOf(ScrollConfig.load(context))
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            println("权限已授予！")
        }
    }

    var isAccessibilityEnabled by remember {
        mutableStateOf(isTouchEngineAccessibilityEnabled(context))
    }

    val accessibilityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isAccessibilityEnabled = isTouchEngineAccessibilityEnabled(context)
    }

    LaunchedEffect(Unit) {
        isAccessibilityEnabled = isTouchEngineAccessibilityEnabled(context)

        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayLauncher.launch(intent)
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    var isServiceRunning by remember { mutableStateOf(sharedPrefs.getBoolean("service_running", true)) }
    var selectedAlgorithm by remember { mutableIntStateOf(0) }
    var ballSize    by remember { mutableFloatStateOf(sharedPrefs.getFloat("ball_size", 0.65f)) }
    var ballOpacity by remember { mutableFloatStateOf(sharedPrefs.getFloat("ball_opacity", 0.80f)) }

    var accentColor by remember { mutableStateOf(Color(0xFF2E6BFF)) }

    val colorOptions = listOf(
        Color(0xFF2E6BFF),
        Color(0xFF10B981),
        Color(0xFF8B5CF6),
        Color(0xFFF43F5E),
        Color(0xFF1F2937)
    )

    val tabItems = listOf(
        "首页" to Icons.Rounded.Home,
        "模式" to Icons.Rounded.Sensors,
        "外观" to Icons.Rounded.Palette,
        "高级" to Icons.Rounded.Tune
    )

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                tabItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.second, contentDescription = item.first) },
                        label = { Text(item.first, fontSize = 11.sp) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = accentColor,
                            selectedTextColor = accentColor,
                            indicatorColor = accentColor.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(BackgroundGray)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    isServiceRunning,
                    selectedAlgorithm,
                    ballSize,
                    ballOpacity,
                    accentColor,
                    isAccessibilityEnabled,
                    openAccessibilitySettings = {
                        accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRunningChange = {
                        isServiceRunning = it
                        sharedPrefs.edit().putBoolean("service_running", it).apply()
                        TouchEngineService.liveEnabled.value = it
                    },
                    navigateToTab = { selectedTab = it }
                )

                1 -> ModeScreen(
                    isServiceRunning,
                    selectedAlgorithm,
                    accentColor,
                    onAlgorithmChange = { selectedAlgorithm = it }
                )

                2 -> AppearanceScreen(
                    isServiceRunning,
                    ballSize,
                    ballOpacity,
                    accentColor,
                    colorOptions,
                    onSizeChange = {
                        ballSize = it
                        sharedPrefs.edit().putFloat("ball_size", it).apply()
                        TouchEngineService.liveBallSize.floatValue = it
                    },
                    onOpacityChange = {
                        ballOpacity = it
                        sharedPrefs.edit().putFloat("ball_opacity", it).apply()
                        TouchEngineService.liveBallOpacity.floatValue = it
                    },
                    onColorChange = { accentColor = it }
                )

                // 🚀 将自动开火状态传递给高级设置页面
                3 -> AdvancedScreen(
                    isEnabled = isServiceRunning,
                    accentColor = accentColor,
                    isAutoClick = isAutoClick,
                    onAutoClickChange = { newState ->
                        isAutoClick = newState
                        sharedPrefs.edit().putBoolean("auto_click", newState).apply()
                    },
                    scrollConfig = scrollConfig,
                    onScrollConfigChange = { newConfig ->
                        scrollConfig = newConfig
                        // 实时保存，TouchEngineService 下次重建 NavMesh 时读取
                        ScrollConfig.save(context, newConfig)
                    }
                )
            }
        }
    }
}

// ================= 页面 1：首页 =================
@Composable
fun HomeScreen(isRunning: Boolean, selectedAlgorithm: Int, ballSize: Float, ballOpacity: Float, accentColor: Color, isAccessibilityEnabled: Boolean, openAccessibilitySettings: () -> Unit, onRunningChange: (Boolean) -> Unit, navigateToTab: (Int) -> Unit) {
    var langExpanded by remember { mutableStateOf(false) }
    var currentLang by remember { mutableStateOf("中") }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "控制中心", fontSize = 16.sp, color = accentColor, fontWeight = FontWeight.Bold)
            Box {
                Row(modifier = Modifier.clickable { langExpanded = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, contentDescription = null, tint = TextDarkGray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = currentLang, fontSize = 14.sp, color = TextDarkGray, fontWeight = FontWeight.Medium)
                }
                DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }, modifier = Modifier.background(Color.White)) {
                    DropdownMenuItem(text = { Text("简体中文") }, onClick = { currentLang = "中"; langExpanded = false })
                    DropdownMenuItem(text = { Text("日本語") }, onClick = { currentLang = "日"; langExpanded = false })
                    DropdownMenuItem(text = { Text("English") }, onClick = { currentLang = "EN"; langExpanded = false })
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        if (!isAccessibilityEnabled) {
            AccessibilityPermissionCard(accentColor, openAccessibilitySettings)
            Spacer(modifier = Modifier.height(16.dp))
        }

        HeroCard(isRunning, accentColor, onRunningChange)
        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "核心参数", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatsTile(title = "${(ballSize * 100).toInt()}%", subtitle = "悬浮尺寸", accentColor = accentColor, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            StatsTile(title = "${(ballOpacity * 100).toInt()}%", subtitle = "背景透明度", accentColor = accentColor, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            val modeText = if (!isRunning) "已休眠" else if (selectedAlgorithm == 0) "节点跳跃" else "磁性指针"
            StatsTile(title = modeText, subtitle = "当前模式", accentColor = accentColor, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(24.dp))

        NavListItem(icon = Icons.Rounded.Sensors, title = "模式选择及灵敏度", accentColor = accentColor) { navigateToTab(1) }
        Spacer(modifier = Modifier.height(12.dp))
        NavListItem(icon = Icons.Rounded.Palette, title = "视觉与外观", accentColor = accentColor) { navigateToTab(2) }
    }
}

// ================= 页面 2：模式 =================
@Composable
fun ModeScreen(isEnabled: Boolean, selectedAlgorithm: Int, accentColor: Color, onAlgorithmChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).alpha(if (isEnabled) 1f else 0.5f)) {
        Text(text = "操作模式", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        Spacer(modifier = Modifier.height(24.dp))

        AlgorithmCard(title = "空间节点跳跃", icon = Icons.Rounded.GridView, isSelected = selectedAlgorithm == 0, enabled = isEnabled, accentColor = accentColor, onClick = { if(isEnabled) onAlgorithmChange(0) })
        Spacer(modifier = Modifier.height(16.dp))
        AlgorithmCard(title = "磁性虚拟指针（开发中）", icon = Icons.Rounded.AdsClick, isSelected = false, enabled = false, accentColor = accentColor, onClick = { })

    }
}

// ================= 页面 3：外观 =================
@Composable
fun AppearanceScreen(isEnabled: Boolean, ballSize: Float, ballOpacity: Float, accentColor: Color, colorOptions: List<Color>, onSizeChange: (Float) -> Unit, onOpacityChange: (Float) -> Unit, onColorChange: (Color) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).alpha(if (isEnabled) 1f else 0.5f)) {
        Text(text = "视觉与外观", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        Spacer(modifier = Modifier.height(24.dp))

        PreviewCard(ballSize, ballOpacity, accentColor)
        Spacer(modifier = Modifier.height(24.dp))

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(20.dp)) {
                SliderRow(label = "悬浮球尺寸", value = ballSize, onValueChange = onSizeChange, accentColor = accentColor, enabled = isEnabled)
                Spacer(modifier = Modifier.height(20.dp))
                SliderRow(label = "悬浮球不透明度", value = ballOpacity, onValueChange = onOpacityChange, accentColor = accentColor, enabled = isEnabled)

                Spacer(modifier = Modifier.height(32.dp))
                Text(text = "强调色 (Accent Color)", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    colorOptions.forEach { color ->
                        ColorButton(color, isSelected = accentColor == color) { onColorChange(color) }
                    }
                }
            }
        }
    }
}

// ================= 页面 4：高级 =================
@Composable
fun AdvancedScreen(
    isEnabled: Boolean,
    accentColor: Color,
    isAutoClick: Boolean,
    onAutoClickChange: (Boolean) -> Unit,
    scrollConfig: ScrollConfig,
    onScrollConfigChange: (ScrollConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .alpha(if (isEnabled) 1f else 0.5f)
    ) {
        Text(text = "智能与高级", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        Spacer(modifier = Modifier.height(24.dp))

        // ── 自动开火 ──────────────────────────────────────
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(20.dp)) {
                ToggleRow(
                    title = "松开摇杆自动开火",
                    subtitle = if (isAutoClick) "松手立刻点击目标" else "松手后目标呼吸闪烁，单击悬浮球确认",
                    checked = isAutoClick,
                    onCheckedChange = onAutoClickChange,
                    accentColor = accentColor,
                    enabled = isEnabled
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFFF0F0F0))
                ToggleRow(
                    title = "重叠目标智能过滤",
                    subtitle = "优先选择面积更小的精准目标",
                    checked = true,
                    onCheckedChange = {},
                    accentColor = accentColor,
                    enabled = isEnabled
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── 自动滚动 / 翻页 ───────────────────────────────
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(20.dp)) {

                // 总开关
                ToggleRow(
                    title = "自动滚动 / 翻页",
                    subtitle = if (scrollConfig.autoScrollEnabled)
                        "焦点到边缘继续拉动时自动滚动屏幕"
                    else
                        "关闭后需手动滑动屏幕",
                    checked = scrollConfig.autoScrollEnabled,
                    onCheckedChange = {
                        onScrollConfigChange(scrollConfig.copy(autoScrollEnabled = it))
                    },
                    accentColor = accentColor,
                    enabled = isEnabled
                )

                // 以下设置项仅在总开关开启时可用
                val scrollEnabled = isEnabled && scrollConfig.autoScrollEnabled

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFFF0F0F0))

                // 提示等待时间
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "提示等待时间",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (scrollEnabled) Color.Black else Color.Gray
                        )
                        Text(
                            "${scrollConfig.hintDurationMs}ms",
                            fontSize = 15.sp,
                            color = if (scrollEnabled) accentColor else Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "到达边缘后显示提示条，等待此时间后触发滑动",
                        fontSize = 12.sp,
                        color = TextDarkGray,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                    )
                    Slider(
                        value = scrollConfig.hintDurationMs.toFloat(),
                        onValueChange = {
                            onScrollConfigChange(scrollConfig.copy(hintDurationMs = it.toLong()))
                        },
                        valueRange = 500f..3000f,
                        enabled = scrollEnabled,
                        colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFFF0F0F0))

                // 滑动距离灵敏度
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "滑动距离",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (scrollEnabled) Color.Black else Color.Gray
                        )
                        Text(
                            "${(scrollConfig.scrollSensitivity * 100).toInt()}%",
                            fontSize = 15.sp,
                            color = if (scrollEnabled) accentColor else Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "每次模拟滑动的距离（相对屏幕高度百分比）",
                        fontSize = 12.sp,
                        color = TextDarkGray,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                    )
                    Slider(
                        value = scrollConfig.scrollSensitivity,
                        onValueChange = {
                            onScrollConfigChange(scrollConfig.copy(scrollSensitivity = it))
                        },
                        valueRange = 0.2f..1.0f,
                        enabled = scrollEnabled,
                        colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFFF0F0F0))

                // 滑动速度
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "滑动速度",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (scrollEnabled) Color.Black else Color.Gray
                        )
                        Text(
                            "${scrollConfig.scrollGestureDurationMs}ms",
                            fontSize = 15.sp,
                            color = if (scrollEnabled) accentColor else Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "模拟手势的持续时间，越短越快（建议 200~600ms）",
                        fontSize = 12.sp,
                        color = TextDarkGray,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                    )
                    Slider(
                        value = scrollConfig.scrollGestureDurationMs.toFloat(),
                        onValueChange = {
                            onScrollConfigChange(scrollConfig.copy(scrollGestureDurationMs = it.toLong()))
                        },
                        valueRange = 100f..800f,
                        enabled = scrollEnabled,
                        colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                    )
                }
            }
        }
    }
}

// ================= 组件库 =================
@Composable
fun AccessibilityPermissionCard(accentColor: Color, onOpenSettings: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.SettingsAccessibility,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "无障碍服务未开启",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "开启后悬浮摇杆才能识别目标并执行点击。",
                        fontSize = 12.sp,
                        color = TextDarkGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("去开启无障碍权限")
            }
        }
    }
}

@Composable
fun HeroCard(isRunning: Boolean, accentColor: Color, onRunningChange: (Boolean) -> Unit) {
    val bgBrush = if (isRunning) {
        Brush.linearGradient(colors = listOf(accentColor.copy(alpha = 0.7f), accentColor))
    } else {
        Brush.linearGradient(colors = listOf(Color(0xFF9CA3AF), Color(0xFF4B5563)))
    }

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bgBrush).padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("系统工具", color = Color.White.copy(0.8f), fontSize = 12.sp)
                Text(if(isRunning) "服务运行中" else "服务已休眠", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            Switch(checked = isRunning, onCheckedChange = onRunningChange, colors = SwitchDefaults.colors(checkedThumbColor = accentColor, checkedTrackColor = Color.White, uncheckedTrackColor = Color.White.copy(0.3f), uncheckedThumbColor = Color.White))
        }
    }
}

@Composable
fun AlgorithmCard(title: String, icon: ImageVector, isSelected: Boolean, enabled: Boolean, accentColor: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if(isSelected && enabled) accentColor.copy(alpha = 0.1f) else Color.White),
        border = if(isSelected && enabled) BorderStroke(2.dp, accentColor) else null
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(if(isSelected && enabled) accentColor else Color(0xFFF0F0F0)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = if(isSelected && enabled) Color.White else Color.Gray)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if(isSelected && enabled) accentColor else Color.Black)
        }
    }
}

@Composable fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit = {}, accentColor: Color, enabled: Boolean) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${(value * 100).toInt()}%", fontSize = 15.sp, color = if(enabled) accentColor else Color.Gray, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, enabled = enabled, colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
    }
}

@Composable fun PreviewCard(sizeFactor: Float, opacity: Float, accentColor: Color) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
            Text("悬浮控件预览", fontSize = 14.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(30.dp));
            Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size((48 + 48 * sizeFactor).dp).clip(CircleShape).background(accentColor.copy(alpha = opacity * 0.2f)), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size((36 + 36 * sizeFactor).dp).clip(CircleShape).background(accentColor.copy(alpha = opacity)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.OpenWith, contentDescription = null, tint = Color.White, modifier = Modifier.size((16 + 16 * sizeFactor).dp))
                    }
                }
            }; Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable fun StatsTile(title: String, subtitle: String, accentColor: Color, modifier: Modifier) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accentColor);
            Text(subtitle, fontSize = 11.sp, color = TextDarkGray)
        }
    }
}

@Composable fun ColorButton(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(color).clickable { onClick() }.border(width = if(isSelected) 3.dp else 0.dp, color = Color.Black.copy(alpha=0.2f), shape = CircleShape))
}

@Composable fun NavListItem(icon: ImageVector, title: String, accentColor: Color, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.LightGray)
        }
    }
}

// 🚀 升级后的 ToggleRow，支持副标题和小字说明
@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.Black)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = TextDarkGray, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
        )
    }
}





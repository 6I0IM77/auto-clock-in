# Shizuku provider / api 的 keep 规则由 aar 自带，这里只保留应用自身入口
-keep class com.shuangka.punch.core.PunchAccessibilityService { *; }
-keep class com.shuangka.punch.App { *; }

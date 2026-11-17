# Project-specific R8 rules. Libraries used here (Media3, Retrofit, OkHttp, kotlinx.serialization,
# Room, Hilt, Coil) ship their own consumer rules, so keep this file minimal.

# Кнопки виджета: Glance создаёт ActionCallback через конструктор без аргументов (рефлексия).
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }

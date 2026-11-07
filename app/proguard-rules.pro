# kotlinx.serialization: сохраняем сгенерированные сериализаторы наших моделей
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.yoru.tv.** {
    *** Companion;
}
-keepclasseswithmembers class app.yoru.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.yoru.tv.**$$serializer { *; }

# WorkManager создаёт воркеры через рефлексию
-keep class app.yoru.tv.channels.SyncWorker { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

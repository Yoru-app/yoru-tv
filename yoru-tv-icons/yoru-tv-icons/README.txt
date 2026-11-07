Иконки Yoru TV

Скопируйте папку app/src/main/res в проект Android TV (с заменой файлов).
В AndroidManifest.xml у <application>:
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    android:banner="@drawable/banner"

banner.png (320x180, drawable-xhdpi) — баннер на главном экране Android TV.
mipmap-*/ic_launcher*.png — обычные иконки, mipmap-anydpi-v26 — адаптивная (фон + маскот).
play-store/ — баннер 1280x720 и иконка 512x512 для Google Play.

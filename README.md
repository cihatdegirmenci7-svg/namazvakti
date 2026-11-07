# Vakit Android uygulaması

Bu Android projesi Vakit arayüzünü yerel WebView içinde açar. Bildirim izni verildiğinde, namaz vakitleriyle güncellenen devamlı kilit ekranı kartı gösterir. Durum çubuğu simgesi, yaklaşan vakte 60 dakikadan az kaldığında hilalin içinde kalan dakika sayısını gösterir. Konum izni ilçe eşleştirmesi ve vakit hesabı içindir.

## APK oluşturma

1. Projeyi Android Studio'da açın.
2. Android SDK API 35 ve gerekli Gradle eklentilerinin kurulmasına izin verin.
3. `Build > Build APK(s)` seçin.
4. Oluşturulan deneme APK'sı `app/build/outputs/apk/debug/app-debug.apk` konumunda olur.

Kilit ekranında görünmesi için Android bildirim izni ve kilit ekranı bildirim görünürlüğü açık olmalıdır. Bazı telefonlarda pil tasarrufu ayarları devamlı bildirimi durdurabilir.

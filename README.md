# BT Mouse

ফোনকে ল্যাপটপের Bluetooth মাউস বানানোর Android অ্যাপ (Android 9+ লাগবে)।

## GitHub দিয়ে APK বানানো

1. GitHub-এ নতুন repository খোলো।
2. এই ফোল্ডারের সব ফাইল (`.github` ফোল্ডারসহ) repository-তে আপলোড করো।
3. `Actions` ট্যাব -> `Build APK` চলবে (৩-৬ মিনিট)।
4. শেষ হলে সেই run-এ ঢুকে নিচে `Artifacts` থেকে `BTMouse-debug-apk` ডাউনলোড করো।
5. zip খুলে `app-debug.apk` ফোনে ইনস্টল করো।

## ব্যবহার

1. ফোনে অ্যাপ খুলে Bluetooth permission দাও।
2. `Make discoverable` চাপো।
3. ল্যাপটপের Bluetooth settings থেকে ফোনটা খুঁজে pair করো।
4. অ্যাপে `Connect` চেপে ল্যাপটপ সিলেক্ট করো।
5. কালো/ধূসর জায়গায় আঙুল নাড়ালে কার্সর নড়বে।
   - এক আঙুলে ট্যাপ = বাম ক্লিক
   - দুই আঙুলে ট্যাপ = ডান ক্লিক
   - দুই আঙুল দিয়ে উপর-নিচ = স্ক্রল
   - নিচের LEFT / RIGHT বাটন চেপে ধরে ড্র্যাগও করা যায়

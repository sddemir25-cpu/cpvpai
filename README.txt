JAR URETME (2 yol)

1) GitHub ile (kurulum gerektirmez):
   - Bu klasoru yeni bir GitHub reposuna yukle (push et)
   - Actions sekmesi > "build" calisir > Artifacts > cpvpai-jar indir
   - Mod jar'ini (sources olmayan) mods klasorune at. Fabric API de gerekli.

2) Kendi bilgisayarinda:
   - JDK 21 + Gradle 9.x kur
   - Bu klasorde: gradle build
   - build/libs/cpvpai-1.0.0.jar

Surum numaralari tutmazsa https://fabricmc.net/develop adresinden
1.21.11 icin guncel loader / fabric-api / loom surumlerini gradle.properties'e yaz.

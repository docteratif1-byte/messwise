# kotlinx.serialization, OkHttp, Coil and Compose ship their own consumer rules.
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,Signature
-renamesourcefileattribute SourceFile
# Data classes decoded from the Messwise API.
-keep,includedescriptorclasses class com.kodawick.messwise.data.model.** { *; }
-keepclassmembers class com.kodawick.messwise.data.model.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**

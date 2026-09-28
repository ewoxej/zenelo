# jaudiotagger looks up tag frame classes by name (Class.forName("...FrameBody" + id)), so R8 must
# keep and not rename them.
-keep class org.jaudiotagger.** { *; }
# Desktop-only image code in jaudiotagger (StandardArtwork); Android uses AndroidArtwork instead.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**

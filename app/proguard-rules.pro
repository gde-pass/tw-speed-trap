# App-specific ProGuard/R8 rules. Empty for now — rules are added alongside the
# features that need them (kotlinx.serialization models, etc.).

# kotlinx.serialization models decoded by the update path; the plugin's
# intrinsics and the library's consumer rules normally suffice, this makes
# the minified build independent of them.
-keep @kotlinx.serialization.Serializable class io.github.gdepass.twspeedtrap.data.** { *; }

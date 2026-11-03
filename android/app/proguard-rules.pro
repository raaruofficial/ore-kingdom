# Ore Kingdom release rules (from the Seeker Citadel shell of record).
# The app is first-party Compose + MWA clientlib; library consumer rules
# cover the rest.
-keep class com.solana.mobilewalletadapter.** { *; }
-dontwarn com.solana.mobilewalletadapter.**

# Annotation-only classes referenced by Tink (transitive via
# androidx.security:security-crypto) are not shipped as runtime
# dependencies; R8's missing-class check fails the release shrink
# without these. Annotations have no runtime presence — dontwarn is
# the standard disposition.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

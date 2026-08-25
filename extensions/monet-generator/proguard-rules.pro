-keep class dev.ujhhgtg.wekit.extensions.monet.api.** { *; }

-keep public class dev.ujhhgtg.wekit.extensions.monet.MonetGeneratorEntrypointV2 {
    public <init>();
    public dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResultV2 generate(
        dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequestV2,
        dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListenerV2
    );
}

-dontwarn javax.naming.**

import com.tgyozalyan.channelmanager.web.EmbeddedTomcat;

private static final String contexts = "classpath:/main-context.xml classpath:/swagger-context.xml";

void main() {
    Locale.setDefault(Locale.US);
    new EmbeddedTomcat(contexts).startServer();
}
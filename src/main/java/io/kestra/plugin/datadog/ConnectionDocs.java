package io.kestra.plugin.datadog;

/** The descriptions of the connection properties, shared by the tasks and the trigger so that they do not drift. */
public final class ConnectionDocs {
    public static final String API_KEY = "The organization API key. Use a Kestra secret, e.g. `{{ secret('DATADOG_API_KEY') }}`.";

    public static final String APP_KEY = """
        An application key. Datadog asks for it on every call that reads data or manages monitors and downtimes, so the tasks that only send data \
        (`events.Create`, `metrics.Submit`, `logs.Send`) work without it. Create it with the scopes the task needs. \
        Use a Kestra secret, e.g. `{{ secret('DATADOG_APP_KEY') }}`.""";

    public static final String SITE = """
        The Datadog site of the organization, the domain you see in the Datadog URL: `datadoghq.com` (US1, the default), `us3.datadoghq.com`, \
        `us5.datadoghq.com`, `datadoghq.eu`, `ap1.datadoghq.com`, `ap2.datadoghq.com`, `uk1.datadoghq.com` or `ddog-gov.com`. \
        A key only works on the site of the organization that created it.""";

    public static final String API_URL = """
        Replaces `https://api.<site>` when set, for a proxy, a private link or a test server. Leave it empty for the public API. \
        `logs.Send` does not use it: it calls the log intake, which has its own address (`intakeUrl`).""";

    private ConnectionDocs() {
    }
}

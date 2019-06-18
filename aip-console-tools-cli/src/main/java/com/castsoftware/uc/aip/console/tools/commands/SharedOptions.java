package com.castsoftware.uc.aip.console.tools.commands;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.List;

@Component
public class SharedOptions {
    /**
     * Connection to AIP Console parameters
     **/
    @CommandLine.Option(names = {"-s", "--server-url"}, paramLabel = "AIP_CONSOLE_URL", description = "The base URL for AIP Console (defaults to ${DEFAULT-VALUE})", defaultValue = "http://localhost:8081")
    private String serverRootUrl;

    @CommandLine.Option(names = {"--apikey"}, description = "The API Key to access AIP Console. Will prompt entry if no value is passed.", interactive = true, arity = "0..1")
    private String apiKey;

    @CommandLine.Option(names = {"--apikey:env"}, paramLabel = "ENV_VAR_NAME", description = "The name of the environment variable containing the AIP Key to access AIP Console")
    private String apiKeyEnvVariable;

    @Getter
    @CommandLine.Option(names = {"--user"}, description = "User name. Use this if no API Key generation is available on AIP Console. Provide the user's password in the apikey parameter.")
    private String username;


    @CommandLine.Unmatched
    private List<String> unmatchedOptions;

    public String getApiKeyValue() {
        if (apiKeyEnvVariable != null) {
            return System.getenv(apiKeyEnvVariable);
        }
        return apiKey;
    }

    public String getFullServerRootUrl() {
        if (StringUtils.isNotBlank(serverRootUrl) && !serverRootUrl.startsWith("http")) {
            serverRootUrl = "http://" + serverRootUrl;
        }
        return serverRootUrl;
    }

    @Override
    public String toString() {
        return "SharedOptions{" +
                "serverRootUrl='" + serverRootUrl + '\'' +
                ", apiKey='omitted'" +
                ", apiKeyEnvVariable='" + apiKeyEnvVariable + '\'' +
                ", username='" + username + '\'' +
                ", unmatchedOptions=" + unmatchedOptions +
                '}';
    }
}
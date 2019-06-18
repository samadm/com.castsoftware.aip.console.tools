package io.jenkins.plugins.aipconsole.extensions;

import com.castsoftware.uc.aip.console.tools.core.exceptions.ApiCallException;
import com.castsoftware.uc.aip.console.tools.core.services.RestApiService;
import com.castsoftware.uc.aip.console.tools.core.utils.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.ExtensionPoint;
import lombok.extern.java.Log;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

@Extension
@Log
public class RestApiServiceImpl implements ExtensionPoint, RestApiService {
    private static final List<Integer> ACCEPTED_HTTP_CODES = Lists.newArrayList(200, 201, 202, 204);

    private OkHttpClient client;
    private ObjectMapper mapper = new ObjectMapper();
    private QueryableCookieJar cookieJar;
    private String serverUrl;
    private String username;
    private String key;

    public RestApiServiceImpl() {
        this.cookieJar = new QueryableCookieJar();
        this.client = new OkHttpClient.Builder()
                .addInterceptor(getAuthInterceptor())
                .cookieJar(cookieJar)
                .build();
    }

    @Override
    public void validateUrlAndKey(String serverUrl, String apiKey) throws ApiCallException {
        assert StringUtils.isNoneBlank(serverUrl, apiKey);
        if (!StringUtils.startsWithIgnoreCase(serverUrl, "http")) {
            serverUrl = "http://" + serverUrl;
        }
        if (StringUtils.endsWithIgnoreCase(serverUrl, "/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        this.serverUrl = serverUrl;
        this.key = apiKey;
        login();
    }

    @Override
    public void validateUrlAndKey(String serverUrl, String username, String password) throws ApiCallException {
        this.username = username;
        validateUrlAndKey(serverUrl, password);
    }

    @Override
    public <T> T getForEntity(String endpoint, Class<T> responseClass) throws ApiCallException {
        return exchangeForEntity("GET", endpoint, null, responseClass);
    }

    @Override
    public <T> T postForEntity(String endpoint, Object entity, Class<T> responseClass) throws ApiCallException {
        return exchangeForEntity("POST", endpoint, entity, responseClass);
    }

    @Override
    public <T> T patchForEntity(String endpoint, Object entity, Class<T> responseClass) throws ApiCallException {
        return exchangeForEntity("PATCH", endpoint, entity, responseClass);
    }

    @Override
    public <T> T putForEntity(String endpoint, Object entity, Class<T> responseClass) throws ApiCallException {
        return exchangeForEntity("PUT", endpoint, entity, responseClass);
    }

    @Override
    public <T> T deleteForEntity(String endpoint, Object entity, Class<T> responseClass) throws ApiCallException {
        return exchangeForEntity("DELETE", endpoint, entity, responseClass);
    }

    @Override
    public <T> T exchangeMultipartForEntity(String method, String endpoint, Map<String, Map<String, String>> headers, Map<String, Object> content, Class<T> responseClass) throws ApiCallException {
        Request.Builder reqBuilder = getRequestBuilder(endpoint);
        log.fine(String.format("Executing MULTIPART call with method %s to endpoint %s", method, endpoint));

        MultipartBody.Builder builder = new MultipartBody.Builder();

        for (Map.Entry<String, Object> entry : content.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            RequestBody body;
            String filename;
            // Add some raw content
            if (value instanceof byte[]) {
                filename = "filechunk";
                body = RequestBody.create(MediaType.parse("application/octet-stream"), (byte[]) value);
            } else {
                filename = null;
                body = getRequestBodyForEntity(value);
            }
            MultipartBody.Part part = MultipartBody.Part.createFormData(key, filename, body);
            builder.addPart(part);
        }

        Request req = reqBuilder.method(method, builder.build())
                .build();

        try (Response response = client.newCall(req).execute()) {
            if (ArrayUtils.contains(new int[]{200, 201, 202, 204}, response.code())) {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    return mapper.readValue(responseBody.byteStream(), responseClass);
                }
            }
            log.log(Level.SEVERE, "Response code from API was unexpected : " + response.code());
            log.log(Level.SEVERE, "Content was " + (response.body() == null ? "EMPTY" : response.body().string()));
            throw new ApiCallException("Unable to execute multipart form data with provided content");
        } catch (IOException e) {
            log.log(Level.SEVERE, "IOException when calling endpoint " + endpoint, e);
            throw new ApiCallException(e);
        }
    }

    private <T> T exchangeForEntity(String method, String endpoint, Object entity, Class<T> responseClass) throws ApiCallException {
        Request request = getRequestBuilder(endpoint)
                .method(method, getRequestBodyForEntity(entity))
                .build();
        log.fine(String.format("Executing call with method %s to endpoint %s", method, endpoint));
        log.finest("Entity is " + entity);

        try (Response response = client.newCall(request).execute()) {
            if (ACCEPTED_HTTP_CODES.contains(response.code())) {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    try (InputStream bodyStream = responseBody.byteStream()) {
                        return mapper.readValue(bodyStream, responseClass);
                    } catch (MismatchedInputException e) {
                        log.warning("Unable to parse object as " + responseClass.getName() + "(expected ?). Returning null instead.");
                        return null;
                    }
                }
                log.fine("No body in response to parse");
                return null;
            }
            String message = "Response code from API was unexpected : " + response.code();
            message += "\nContent was " + (response.body() == null ? "EMPTY" : response.body().string());
            throw new ApiCallException(message);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Unable to send request", e);
            throw new ApiCallException(e);
        }
    }

    private void login() throws ApiCallException {
        Request request = getRequestBuilder("/api/user")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (ArrayUtils.contains(new int[]{200, 201, 202, 204}, response.code())) {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    log.finest("Body is " + responseBody.string());
                }
                log.info("Login successful");
                return;
            }
            log.severe("Login to AIP Console failed (http status is " + response.code() + ")");
            log.severe("Content was " + (response.body() == null ? "EMPTY" : response.body().string()));
            throw new ApiCallException("Unable to login to AIP Console");
        } catch (IOException e) {
            log.log(Level.SEVERE, "Unable to send request", e);
            throw new ApiCallException(e);
        }
    }

    private Request.Builder getRequestBuilder(String endpoint) {
        String url;
        if (StringUtils.startsWithIgnoreCase(endpoint, "/")) {
            url = this.serverUrl + endpoint;
        } else {
            url = this.serverUrl + "/" + endpoint;
        }

        Request.Builder builder = new Request.Builder();
        builder.url(url);

        return builder;
    }

    private RequestBody getRequestBodyForEntity(Object entity) throws ApiCallException {
        if (entity == null) {
            return null;
        }
        try {
            return RequestBody.create(
                    MediaType.parse("application/json"),
                    mapper.writeValueAsString(entity));
        } catch (JsonProcessingException e) {
            log.log(Level.SEVERE, "Unable to map object of type " + entity.getClass().getName() + " to JSON", e);
            throw new ApiCallException(e);
        }
    }

    /**
     * Create an interceptor to add authentication headers
     * <p/>
     * It'll also add XSRF Token to the request (to avoid 403s)
     *
     * @return an Interceptor instance that'll add Authentication headers if necessary
     */
    private Interceptor getAuthInterceptor() {
        return new AipLoginInterceptor();
    }

    /**
     * Simple cookie jar impl with ability to query a cookie value
     */
    private static class QueryableCookieJar implements CookieJar {
        private Set<Cookie> cookieSet = new HashSet<>();

        @Override
        @ParametersAreNonnullByDefault
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            cookies.forEach(c -> {
                Optional<Cookie> optCookie = cookieSet.stream()
                        .filter(prevCookie -> StringUtils.equalsIgnoreCase(c.name(), prevCookie.name()))
                        .findFirst();
                optCookie.ifPresent(cookie -> cookieSet.remove(cookie));
                cookieSet.add(c);
            });
        }

        @Override
        @ParametersAreNonnullByDefault
        public List<Cookie> loadForRequest(HttpUrl url) {
            return new ArrayList<>(cookieSet);
        }

        public Cookie getCookieByName(String name) {
            return cookieSet
                    .stream()
                    .filter(c -> StringUtils.equalsIgnoreCase(c.name(), name))
                    .findFirst()
                    .orElse(null);
        }
    }

    private class AipLoginInterceptor implements Interceptor {
        private final long xsrfCookieTtl = TimeUnit.MINUTES.toMillis(5);
        private long xsrfExpirationTime = 0L;

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            Cookie xsrfCookie = cookieJar.getCookieByName("XSRF-TOKEN");

            Response response;
            Request.Builder reqBuilder = request.newBuilder();

            // get xsrf cookie
            if (xsrfCookie != null &&
                    System.currentTimeMillis() < xsrfExpirationTime) {

                log.fine("Cookie and expiration time not passed (" + xsrfExpirationTime + ")");
                reqBuilder.header("X-XSRF-TOKEN", xsrfCookie.value());
            } else {
                log.fine("No xsrf cookie, next request should set it");
                xsrfExpirationTime = System.currentTimeMillis() + xsrfCookieTtl;
            }

            if (request.header("Authorization") != null ||
                    request.header(Constants.API_KEY_HEADER) != null) {
                // authentication already defined
                response = chain.proceed(reqBuilder.build());
            } else {
                if (!StringUtils.isBlank(username)) {
                    reqBuilder.header("Authorization", Credentials.basic(username, key));
                } else {
                    reqBuilder.header(Constants.API_KEY_HEADER, key);
                }
                response = chain.proceed(reqBuilder.build());
            }

            return response;
        }
    }
}
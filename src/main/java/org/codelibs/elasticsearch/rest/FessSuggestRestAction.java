package org.codelibs.elasticsearch.rest;

import static org.elasticsearch.rest.RestStatus.OK;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codelibs.elasticsearch.service.FessSuggestService;
import org.codelibs.fess.suggest.Suggester;
import org.codelibs.fess.suggest.entity.SuggestItem;
import org.codelibs.fess.suggest.exception.SuggesterException;
import org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.base.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;

public class FessSuggestRestAction extends BaseRestHandler {

    public static final String PARAM_INDEX = "index";
    public static final String PARAM_QUERY = "q";
    public static final String PARAM_SIZE = "size";
    public static final String PARAM_TAGS = "tags";
    public static final String PARAM_ROLES = "roles";
    public static final String PARAM_FIELDS = "fields";

    public static final String SETTINGS_NGWORD_KEY = "fsuggest.ngquery";

    private static final String SEP_PARAM = ",";

    protected final ThreadPool threadPool;

    protected final Set<String> ngQueries;

    protected final FessSuggestService fessSuggestService;

    @Inject
    public FessSuggestRestAction(final Settings settings, final Client client,
            final RestController controller, final ThreadPool threadPool, final FessSuggestService fessSuggestService) {
        super(settings, controller, client);

        this.threadPool = threadPool;

        this.ngQueries = getNgQuerySet(settings);

        this.fessSuggestService = fessSuggestService;

        controller.registerHandler(RestRequest.Method.GET,
            "/{index}/_fsuggest", this);
        controller.registerHandler(RestRequest.Method.GET,
            "/{index}/{type}/_fsuggest", this);
    }

    @Override
    protected void handleRequest(final RestRequest request,
            final RestChannel channel, final Client client) {
        threadPool.executor(ThreadPool.Names.SUGGEST).execute( () -> {
            try {
                final String index = request.param(PARAM_INDEX);
                final String query = request.param(PARAM_QUERY);
                final int size = request.paramAsInt(PARAM_SIZE, 10);
                final String tags = request.param(PARAM_TAGS);
                final String roles = request.param(PARAM_ROLES);
                final String fields = request.param(PARAM_FIELDS);

                if(Strings.isNullOrEmpty(query) || isNgQuery(query)) {
                    try {
                        final XContentBuilder builder = JsonXContent.contentBuilder();
                        final String pretty = request.param("pretty");
                        if (pretty != null && !"false".equalsIgnoreCase(pretty)) {
                            builder.prettyPrint().lfAtEnd();
                        }
                        builder.startObject();
                        builder.field("index", request.param("index"));
                        builder.field("took", 0);
                        builder.field("total", 0);
                        builder.field("num", 0);
                        builder.endObject();
                        channel.sendResponse(new BytesRestResponse(OK, builder));
                    } catch (IOException e) {
                        sendErrorResponse(channel, e);
                    }
                    return;
                }

                final Suggester suggester = fessSuggestService.suggester(index);
                final SuggestRequestBuilder suggestRequestBuilder = suggester.suggest().setSize(size);
                if (!Strings.isNullOrEmpty(query)) {
                    suggestRequestBuilder.setQuery(query);
                }
                if (!Strings.isNullOrEmpty(tags)) {
                    final String[] tagsArray = tags.split(SEP_PARAM);
                    for (final String tag : tagsArray) {
                        suggestRequestBuilder.addTag(tag);
                    }
                }
                if (!Strings.isNullOrEmpty(roles)) {
                    final String[] rolesArray = roles.split(SEP_PARAM);
                    for (final String role : rolesArray) {
                        suggestRequestBuilder.addRole(role);
                    }
                }
                if (!Strings.isNullOrEmpty(fields)) {
                    final String[] fieldsArray = fields.split(SEP_PARAM);
                    for (final String field : fieldsArray) {
                        suggestRequestBuilder.addField(field);
                    }
                }

                suggestRequestBuilder.execute()
                    .done(r -> {
                        try {
                            final XContentBuilder builder = JsonXContent.contentBuilder();
                            final String pretty = request.param("pretty");
                            if (pretty != null && !"false".equalsIgnoreCase(pretty)) {
                                builder.prettyPrint().lfAtEnd();
                            }
                            builder.startObject();
                            builder.field("index", r.getIndex());
                            builder.field("took", r.getTookMs());
                            builder.field("total", r.getTotal());
                            builder.field("num", r.getNum());
                            final List<SuggestItem> suggestItems = r.getItems();
                            if (suggestItems.size() > 0) {
                                builder.startArray("hits");
                                for (final SuggestItem item : suggestItems) {
                                    builder.startObject();
                                    builder.field("text", item.getText());
                                    builder.array("tags", item.getTags());
                                    builder.array("roles", item.getRoles());
                                    builder.array("fields", item.getFields());
                                    builder.endObject();
                                }
                                builder.endArray();
                            }

                            builder.endObject();
                            channel.sendResponse(new BytesRestResponse(OK, builder));
                        } catch (final IOException e) {
                            sendErrorResponse(channel, e);
                        }
                    }).error(t ->
                        sendErrorResponse(channel, t)
                );
            } catch (final SuggesterException e) {
                sendErrorResponse(channel, e);
            }
        });
    }

    private void sendErrorResponse(final RestChannel channel, final Throwable t) {
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to process the request.", t);
            }
            channel.sendResponse(new BytesRestResponse(channel, t));
        } catch (final IOException e) {
            logger.error("Failed to send a failure response.", e);
        }
    }

    private boolean isNgQuery(final String query) {
        return ngQueries.contains(query);
    }

    private Set<String> getNgQuerySet(final Settings settings) {
        final Set<String> ngQueries = Collections.synchronizedSet(new HashSet<String>());
        final String value = settings.get(SETTINGS_NGWORD_KEY);
        if(Strings.isNullOrEmpty(value)) {
            return ngQueries;
        }

        final String[] values = value.split(",");
        for(final String ngQuery: values) {
            if(ngQuery.length() == 0) {
                continue;
            }
            ngQueries.add(ngQuery);
        }
        return ngQueries;
    }

}

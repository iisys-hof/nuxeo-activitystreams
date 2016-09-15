package de.hofuniversity.iisys.nuxeo.activitystreams.agg;

import org.json.JSONObject;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;

public interface IAggregator
{
	public void handleActivity(JSONObject activity, String user);
	
	public boolean handleAggregation(final Event event, final DocumentModel model, String user);
	
	public void cleanup() throws Exception;
	
	public void shutdown();
}

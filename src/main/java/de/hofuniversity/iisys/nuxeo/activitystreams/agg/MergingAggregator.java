package de.hofuniversity.iisys.nuxeo.activitystreams.agg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;

import de.hofuniversity.iisys.nuxeo.activitystreams.ActivitySender;

public class MergingAggregator extends TimeoutAggregator
{
	private final ActivitySender fActSender;
	
	private final Map<String, Long> fLastCreates, fLastUpdates, fLastRemoves, fLastDeletes;
	
	private final Map<String, List<JSONObject>> fCreates, fUpdates, fRemoves, fDeletes;
	
	private final long fAggregateTime;
	
	public MergingAggregator(ActivitySender actSender, long aggTime)
	{
		super(actSender, aggTime);
		
		fLastCreates = new HashMap<String, Long>();
		fLastUpdates = new HashMap<String, Long>();
		fLastRemoves = new HashMap<String, Long>();
		fLastDeletes = new HashMap<String, Long>();
		
		fCreates = new HashMap<String, List<JSONObject>>();
		fUpdates = new HashMap<String, List<JSONObject>>();
		fRemoves = new HashMap<String, List<JSONObject>>();
		fDeletes = new HashMap<String, List<JSONObject>>();
		
		fActSender = actSender;
		
		fAggregateTime = aggTime;
	}

	@Override
	public void handleActivity(JSONObject activity, String user)
	{
		// TODO: aggregate
		boolean send = true;
		
		try
		{
			String verb = activity.getString("verb");
			String actorId = null;
			String documentId = null;
			String documentName = null;
			String documentPath = null;

			if(activity.has("actor"))
			{
				actorId = activity.getJSONObject("actor").getString("id");
			}
			
			JSONObject object = activity.optJSONObject("object");
			if(object != null)
			{
				documentId = object.getString("id");
				documentName = object.optString("displayName");
				documentPath = object.optString("url");
			}
			
			// TODO: will this actually filter out everything that is not a document?
			// TODO: what about folders? deactivated globally at the moment
			if(("add".equals(verb)
				|| "update".equals(verb)
				|| "remove".equals(verb)
				|| "delete".equals(verb))
				&& actorId != null
				&& documentId != null
				&& documentName != null
				&& documentPath != null)
			{
				// strip "/@view_documents" from path
				documentPath = documentPath.substring(0, documentPath.indexOf("/@view_documents"));
				
				// strip filename from path
				documentPath = documentPath.substring(0, documentPath.lastIndexOf("/"));
				
				switch(verb)
				{
					case "add":
						addEntryForPath(fCreates, fLastCreates, actorId, documentPath, object);
						break;

					case "update":
						addEntryForPath(fUpdates, fLastUpdates, actorId, documentPath, object);
						break;

					case "remove":
						addEntryForPath(fRemoves, fLastRemoves, actorId, documentPath, object);
						break;

					case "delete":
						addEntryForPath(fDeletes, fLastDeletes, actorId, documentPath, object);
						break;
				}
				
				
				send = false;
			}
		}
		catch(Exception e)
		{
			fActSender.logException(e);
		}
		
		if(send)
		{
			// only send directly if not handled by aggregation
			fActSender.send(activity, user);
		}
	}
	
	private void addEntryForPath(Map<String, List<JSONObject>> entryMap, Map<String, Long> times,
		String user, String path, JSONObject entry)
	{
		// track paths per user
		path = user + "¶" + path;
		
		List<JSONObject> entries = entryMap.get(path);
		
		if(entries == null)
		{
			entries = new ArrayList<JSONObject>();
			synchronized(entryMap)
			{
				entryMap.put(path, entries);
			}
		}
		
		synchronized(entries)
		{
			entries.add(entry);
		}
		
		// TODO: store timestamp
		handleTimeout(times, path);
	}

	@Override
	public boolean handleAggregation(Event event, DocumentModel model, String user)
	{
		return true;
	}

	@Override
	public void cleanup()
	{
		// TODO: necessary?
//		super.cleanup();
		
		// generate and send aggregated activities
		// send activities based on timed out aggregation slots
		handleCleanup("add", fLastCreates, fCreates);
		handleCleanup("update", fLastUpdates, fUpdates);
		handleCleanup("remove", fLastRemoves, fRemoves);
		handleCleanup("delete", fLastDeletes, fDeletes);
	}

	private void handleCleanup(String verb, Map<String, Long> times,
		Map<String, List<JSONObject>> entries)
	{
		Long time = null;
		Set<String> keys = null;
		
		long delTime = System.currentTimeMillis() - fAggregateTime;
		
		synchronized(times)
		{
			synchronized(entries)
			{
				keys = times.keySet();
				
				for(String key : keys)
				{
					time = times.get(key);
					
					// aggregation window has timed out, send collected entries
					if(time < delTime)
					{
						fActSender.logMessage("### timeout: " + key);
						
						times.remove(key);
						
						// send aggregated activity
						try
						{
							List<JSONObject> docs = entries.remove(key);
							sendActivity(key, verb, docs);
						}
						catch(Exception e)
						{
							fActSender.logException(e);
						}
					}
					else
					{
						fActSender.logMessage("### not timed out yet: " + key);
					}
				}
			}
		}
	}
	
	private void sendActivity(String comboPath, String verb, List<JSONObject> entries)
		throws Exception
	{
		String[] components = comboPath.split("¶");
		String user = components[0];
		String path = components[1] + "/@view_documents";
		
		JSONObject activity = new JSONObject();
		
		activity.put("verb", verb);
		
		// TODO: title required?
		
		// generate actor based on user ID
		JSONObject actor = new JSONObject();
		actor.put("id", user);
		actor.put("displayName", fActSender.getUserName(user));
		actor.put("objectType", "person");
		activity.put("actor", actor);
		
		// TODO: generate object based on entry list 
		JSONObject object = null;
		if(entries.size() > 1)
		{
			// multiple aggregated documents
			object = new JSONObject();
			// TODO: ID required?
			// TODO: internationalization
			object.put("displayName", entries.size() + " Dokumente");
			object.put("objectType", "nuxeoCollection");
			
			// attach document IDs?
			String content = "";
			Iterator<JSONObject> eIter = entries.iterator();
			while(eIter.hasNext())
			{
				content += eIter.next().getString("id");
				if(eIter.hasNext())
				{
					content += ',';
				}
			}
			object.put("content", content);
		}
		else
		{
			// single entry - use normal nuxeo document object
			object = entries.get(0);
		}
		activity.put("object", object);
		
		
		// generate target based on folder
		JSONObject target = new JSONObject();
		// TODO: ID required?
		// TODO: internationlization
		target.put("displayName", "Ordner");
		target.put("objectType", "nuxeoCollection");
		target.put("url", path);
		activity.put("target", target);
		
		// fixed nuxeo generator
		JSONObject generator = fActSender.getGenerator();
		activity.put("generator", generator);
		
		// send
		fActSender.send(activity, user);
	}
	
	@Override
	public void shutdown()
	{
		// send remaining activities
		sendAll("add", fLastCreates, fCreates);
		sendAll("update", fLastUpdates, fUpdates);
		sendAll("remove", fLastRemoves, fRemoves);
		sendAll("delete", fLastDeletes, fDeletes);
	}
	
	private void sendAll(String verb, Map<String, Long> times,
			Map<String, List<JSONObject>> entries)
	{
		Set<String> keys = null;
		
		synchronized(times)
		{
			synchronized(entries)
			{
				keys = times.keySet();
				
				for(String key : keys)
				{
					fActSender.logMessage("### timeout: " + key);
					
					times.remove(key);
					
					// send aggregated activity
					try
					{
						List<JSONObject> docs = entries.remove(key);
						sendActivity(key, verb, docs);
					}
					catch(Exception e)
					{
						fActSender.logException(e);
					}
				}
			}
		}
	}
}

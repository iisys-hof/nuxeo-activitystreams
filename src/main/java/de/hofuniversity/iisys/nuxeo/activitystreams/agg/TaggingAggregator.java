package de.hofuniversity.iisys.nuxeo.activitystreams.agg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.json.JSONObject;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.event.Event;

import de.hofuniversity.iisys.nuxeo.activitystreams.ActivitySender;

public class TaggingAggregator extends TimeoutAggregator
{
	private static final String AGGREG_SEP = ":";
	
	private final ActivitySender fActSender;
	
	private final Map<String, Long> fTagUpdates;
	
	private final Map<String, List<String>> fAggTagAdds, fAggTagRemovals;
	
	private final long fTagAggregateTime;
	
	public TaggingAggregator(ActivitySender actSender, long aggTime, long tagAggTime)
	{
		super(actSender, aggTime);
		
		fActSender = actSender;
		
		fTagUpdates = new HashMap<String, Long>();
		
		fAggTagAdds = new HashMap<String, List<String>>();
		fAggTagRemovals = new HashMap<String, List<String>>();
		
		fTagAggregateTime = tagAggTime;
	}

	@Override
	public void handleActivity(JSONObject activity, String user)
	{
		// not really reasonable to block at this stage
		fActSender.send(activity, user);
	}

	@Override
	public boolean handleAggregation(Event event, DocumentModel model, String user)
	{
		boolean send = true;
		
		final String eventType = event.getName();
		final String modelType = model.getType();
		
		//admin tagging
		if("Tagging".equals(modelType))
		{
			send = adminTagging(event, model, user);
		}
		else if("documentTagUpdated".equals(eventType))
		{
			//user tagging
			//per-user aggregation
			String uuid = model.getVersionSeriesId() + AGGREG_SEP + user;
			
			handleTimeout(fTagUpdates, uuid);
		}
		
		return send;
	}
	
	private boolean adminTagging(Event event, DocumentModel model, String user)
	{
		final String eventType = event.getName();
		
		//different target
		String targetId = fActSender.getPrimaryDocument(event).getPart("relation")
				.get("relation:source").getValue().toString();
		DocumentModel tarMod = event.getContext().getCoreSession().getDocument(
					new IdRef(targetId));
		String uuid = tarMod.getVersionSeriesId();
		
		//aggregate per person - otherwise: send to which activitystream?
		//per-user aggregation
		uuid += AGGREG_SEP + user;
		
		//get unprocessed tag name
		JSONObject tagObj = fActSender.getDocument(model);
		String tag = tagObj.optString("displayName");
		
		//always block, only send aggregated tags
		//only note time for lazy sending
		handleTimeout(fTagUpdates, uuid);
		
		//get aggregated tag actions by user
		List<String> tagAdds = fAggTagAdds.get(uuid);
		if(tagAdds == null)
		{
			synchronized(fAggTagAdds)
			{
				tagAdds = new ArrayList<String>();
				fAggTagAdds.put(uuid, tagAdds);
			}
		}
		
		List<String> tagRems = fAggTagRemovals.get(uuid);
		if(tagRems == null)
		{
			synchronized(fAggTagRemovals)
			{
				tagRems = new ArrayList<String>();
				fAggTagRemovals.put(uuid, tagRems);
			}
		}

		synchronized(fAggTagAdds) { synchronized(fAggTagRemovals)
		{
			//tagging
			if("documentCreated".equals(eventType))
			{
					if(tagRems.contains(tag))
					{
						//removed previously, added again
						tagRems.remove(tag);
					}
					else if(!tagAdds.contains(tag))
					{
						//not yet added or removed
						tagAdds.add(tag);
					}
			}
			//untagging
			else if("aboutToRemove".equals(eventType))
			{
				if(tagAdds.contains(tag))
				{
					//added previously, removed again
					tagAdds.remove(tag);
				}
				else if(!tagRems.contains(tag))
				{
					//not yet added or removed
					tagRems.add(tag);
				}
			}
		}}
		
		return true;
	}

	@Override
	public void cleanup()
	{
		super.cleanup();
		
		Long time = null;
		Set<String> keys = null;
		
		long delTime = System.currentTimeMillis() - fTagAggregateTime;
		synchronized(fTagUpdates)
		{
			keys = new HashSet<String>(fTagUpdates.keySet());
			
			for(String key : keys)
			{
				time = fTagUpdates.get(key);
				String[] tuple = key.split(AGGREG_SEP);
				
				if(time < delTime)
				{
					fTagUpdates.remove(key);
					
					boolean nolist = true;
					
					//send aggregates
					synchronized(fAggTagRemovals)
					{
						List<String> tags = fAggTagRemovals.remove(key);
						if(tags != null && !tags.isEmpty())
						{
							fActSender.sendTagAggAct(tuple[0], tuple[1], tags, false);
							nolist = false;
						}
					}
					
					synchronized(fAggTagAdds)
					{
						List<String> tags = fAggTagAdds.remove(key);
						if(tags != null && !tags.isEmpty())
						{
							fActSender.sendTagAggAct(tuple[0], tuple[1], tags, true);
							nolist = false;
						}
					}
					
					//send generic activity if no list is found
					if(nolist)
					{
						fActSender.sendTagAggAct(tuple[0], tuple[1], null, true);
					}
				}
			}
		}
	}

	@Override
	public void shutdown()
	{
		super.shutdown();
		
		String[] tuple = null;
		
		//send remaining tagging activities
		synchronized(fAggTagAdds)
		{
			for(Entry<String, List<String>> addTagE : fAggTagAdds.entrySet())
			{
				tuple = addTagE.getKey().split(AGGREG_SEP);
				fActSender.sendTagAggAct(tuple[0], tuple[1], addTagE.getValue(), true);
			}
		}

		synchronized(fAggTagRemovals)
		{
			for(Entry<String, List<String>> remTagE : fAggTagRemovals.entrySet())
			{
				tuple = remTagE.getKey().split(AGGREG_SEP);
				fActSender.sendTagAggAct(tuple[0], tuple[1], remTagE.getValue(), false);
			}
		}
	}

}

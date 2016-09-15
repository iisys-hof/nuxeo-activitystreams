package de.hofuniversity.iisys.nuxeo.activitystreams.agg;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;

import de.hofuniversity.iisys.nuxeo.activitystreams.ActivitySender;

public class TimeoutAggregator implements IAggregator
{
	private final ActivitySender fActSender;
	
	private final Set<Map<String, Long>> fTimeStamps;
	private final Map<String, Long> fLastUpdates, fPubUpdates;
	
	private final long fAggregateTime;
	
	public TimeoutAggregator(ActivitySender actSender, long aggTime)
	{
		fTimeStamps = new HashSet<Map<String,Long>>();

		fLastUpdates = new HashMap<String, Long>();
		fPubUpdates = new HashMap<String, Long>();
		
		fActSender = actSender;
		fAggregateTime = aggTime;
	}

	@Override
	public void handleActivity(JSONObject activity, String user)
	{
		// not really possible to block at this stage
		fActSender.send(activity, user);
	}

	@Override
	public boolean handleAggregation(Event event, DocumentModel model, String user)
	{
		boolean send = true;
		final String uuid = model.getVersionSeriesId();
		
		final String eventType = event.getName();
		final String modelType = model.getType();
		
		if(!"documentWaitingPublication".equals(eventType)
			&& !"Tagging".equals(modelType)
			&& !"documentTagUpdated".equals(eventType))
		{
			//normal update events
			send = handleTimeout(fLastUpdates, uuid);
		}
		else if(!"Tagging".equals(modelType)
			&& !"documentTagUpdated".equals(eventType))
		{
			//publication workflow events
			send = handleTimeout(fPubUpdates, uuid);

			//TODO: lazy aggregation with other publication workflow events
		}
		
		return send;
	}

	@Override
	public void cleanup()
	{
		Long time = null;
		Set<String> keys = null;
		
		long delTime = System.currentTimeMillis() - fAggregateTime;
		
		synchronized(fTimeStamps)
		{
			for(Map<String, Long> timeStamps : fTimeStamps)
			{
				synchronized(timeStamps)
				{
					keys = new HashSet<String>(timeStamps.keySet());
					
					for(String key : keys)
					{
						time = timeStamps.get(key);
						
						if(time < delTime)
						{
							timeStamps.remove(key);
						}
					}
				}
			}
		}
	}

	@Override
	public void shutdown()
	{
		// nothing to do
	}
	
	public boolean handleTimeout(Map<String, Long> timeStamps, String uuid)
	{
		boolean send = true;
		
		synchronized(fTimeStamps)
		{
			fTimeStamps.add(timeStamps);
		}
		
		synchronized(timeStamps)
		{
			final Long time = timeStamps.get(uuid);
			final long currTime = System.currentTimeMillis();
			
			//no time stamp logged so far
			if(time == null)
			{
				timeStamps.put(uuid, currTime);
			}
			//still within aggregation window
			else if(currTime < time + fAggregateTime)
			{
				send = false;
			}
			//time stamp too old
			else
			{
				timeStamps.put(uuid, currTime);
			}
		}
		
		return send;
	}
}

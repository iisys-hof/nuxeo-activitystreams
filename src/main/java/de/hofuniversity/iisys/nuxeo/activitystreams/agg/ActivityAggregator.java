package de.hofuniversity.iisys.nuxeo.activitystreams.agg;

import java.util.Map;

import org.json.JSONObject;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;

import de.hofuniversity.iisys.nuxeo.activitystreams.ActivityLogger;
import de.hofuniversity.iisys.nuxeo.activitystreams.ActivitySender;

public class ActivityAggregator implements Runnable, IAggregator
{
	private static final String AGGREGATE_PROP = "activities.aggregate";
	private static final String AGGREGATE_LAZY_PROP =
			"activities.aggregate.lazy";
	private static final String AGGREGATE_INT_PROP =
			"activities.aggregate.interval";
	private static final String TAG_AGGREGATE_INT_PROP =
			"activities.tags.aggregate.interval";
	
	private final Map<String, String> fProperties;
	
	private final ActivitySender fActSender;
	
	private final ActivityLogger fActLogger;
	
	private final boolean fAggregate, fAggregateLazy;
	private final long fAggregateTime, fTagAggregateTime;
	
	private final MergingAggregator fMergingAgg;
	
	private final TaggingAggregator fTaggingAgg;
	
	private final TimeoutAggregator fTimeoutAgg;
	
	public ActivityAggregator(Map<String, String> properties, ActivitySender actSender,
			ActivityLogger actLogger)
	{
		fProperties = properties;
		
		fActSender = actSender;
		fActLogger = actLogger;

        fAggregate = Boolean.parseBoolean(fProperties.get(AGGREGATE_PROP));
        fAggregateLazy = Boolean.parseBoolean(fProperties.get(
        		AGGREGATE_LAZY_PROP));
        fAggregateTime = Long.parseLong(fProperties.get(AGGREGATE_INT_PROP));
        fTagAggregateTime = Long.parseLong(fProperties.get(TAG_AGGREGATE_INT_PROP));

		fMergingAgg = new MergingAggregator(fActSender, fAggregateTime);
		
		fTaggingAgg = new TaggingAggregator(fActSender, fAggregateTime, fTagAggregateTime);
		
		fTimeoutAgg = new TimeoutAggregator(fActSender, fAggregateTime);
		
		//initialize aggregation time stamp cleanup
		if(fAggregate)
		{
			new Thread(this).start();
		}
	}
	
	public void handleActivity(JSONObject activity, String user)
	{
		if(!fAggregate)
		{
			fActSender.send(activity, user);
		}
		else
		{
			// aggregate CRUD operations
			fMergingAgg.handleActivity(activity, user);
		}
	}
	
	public boolean handleAggregation(final Event event,
			final DocumentModel model, String user) throws ClientException
	{
		// always send if deactivated
		if(!fAggregate)
		{
			return true;
		}
		
		
		final String eventType = event.getName();
		final String modelType = model.getType();
		
		//TODO: per-type timeouts
		
		//ignore most event types
		if(!"documentCreated".equals(eventType)
			&& !"documentCreatedByCopy".equals(eventType)
			&& !"documentModified".equals(eventType)
			&& !"documentSecurityUpdated".equals(eventType)
			&& !"documentWaitingPublication".equals(eventType)
			&& !"documentTagUpdated".equals(eventType)
			&& !"Tagging".equals(modelType))
		{
			return true;
		}
		
		//handle aggregation and timeouts
		boolean send = true;
		
		//TODO: lazy sending
		
		send = fTimeoutAgg.handleAggregation(event, model, user);
		
		if("Tagging".equals(modelType)
			|| "documentTagUpdated".equals(eventType))
		{
			//tagging events
			send = fTaggingAgg.handleAggregation(event, model, user);
		}
		
		return send;
	}

	@Override
	public void run()
	{
		// aggregation cleanup
		boolean active = true;
		
		addShutdownHook();
		
		while(active)
		{
			try
			{
				cleanup();
				
				Thread.sleep(fAggregateTime);
			}
			catch(Exception e)
			{
				fActLogger.logString("cleanup stopped: " + e.getLocalizedMessage());
				active = false;
			}
		}
	}

	@Override
	public void cleanup() throws Exception
	{
		//merging
		fMergingAgg.cleanup();
		
		//tagging
		fTaggingAgg.cleanup();
		
		//timeouts
		fTimeoutAgg.cleanup();
	}

	@Override
	public void shutdown()
	{
		// TODO: doesn't really work like this
		
		fMergingAgg.shutdown();
		fTaggingAgg.shutdown();
		fTimeoutAgg.shutdown();
	}

	private void addShutdownHook()
	{
		//add shutdown listener to send remaining tag aggregations
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			@Override
			public void run()
			{
				shutdown();
			}
		});
	}
}

package de.hofuniversity.iisys.nuxeo.activitystreams;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ResourceBundle;
import java.util.Set;

import org.json.JSONObject;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

import de.hofuniversity.iisys.nuxeo.activitystreams.agg.ActivityAggregator;

/**
 * Event listener sending internal activities to an Apache Shindig instance.
 * It is registered via the OSGI-INF/extensions/....ActivityComponent.xml file.
 */
public class ActivitySender	implements EventListener
{
	private static final String PROPERTIES = "activitystreams";
	private static final String LANG_PROPS = "activitystreams_lang";
	
	private static final String SHINDIG_URL_PROP = "shindig.url";
	private static final String NUXEO_URL_PROP = "nuxeo.url";
	private static final String OUTPUT_FILE_PROP = "debug.logfile";
	
	private static final String LANG_PROP = "language";
	
	private static final String LOGGING_PROP = "debug.logging";
	private static final String DOC_URL_PROP = "document.url.mode";
	private static final String IGNORE_FOLDERS_PROP = "folders.ignore";

	private static final String BLOCK_COLLS_PROP = "collections.ignore";
	private static final String BLOCK_FAVORITES_PROP = "favorites.ignore";
	
	private static final String BLOCK_RNODES_PROP = "routenodes.ignore";
	private static final String BLOCK_TASKDOCS_PROP = "taskdocs.ignore";
	private static final String BLOCK_NONSNAPS_PROP =
			"updates.nonsnapshots.ignore";
	
	private static final String ACT_STR_FRAG = "social/rest/activitystreams/";
	
	private static final String ACT_OBJ_ID_PROP = "nuxeo.activityobject.id";
	private static final String ACT_OBJ_NAME_PROP =
			"nuxeo.activityobject.displayName";
	private static final String ACT_OBJ_TYPE_PROP =
			"nuxeo.activityobject.objectType";
	
	private static final String DOC_TO_TRASH = "documentMovedToTrash";
	private static final String DOC_VERSION_TO_TRASH =
			"documentVersionMovedToTrash";
	
	private static final String PROFILE_NAME = "names.profile";
	private static final String COMMENT_NAME = "names.comment";
	private static final String APPROVAL_NAME = "names.approval";
	
	private final Map<String, String> fProperties;
	
	private final Set<String> fBlackList, fObjectBlackList;
	private final Map<String, String> fVerbMap, fTitleMap;
	private final JSONObject fGenerator;

	private final String fShindigUrl;
	private final String fNuxeoUrl;
	private final String fOutputFile;

	private final String fProfileName;
	private final String fCommentName;
	private final String fApprovalName;
	
	private final ActivityLogger fActLogger;
	
	private final boolean fLogging, fIgnoreFolders, fDownloadUrls;
	
	private final boolean fBlockColls, fBlockFavorites;
	private final boolean fBlockRouteNodes, fBlockTaskDocs, fBlockNonSnapshots;

	private final ActivityAggregator fAggregator;
	
	private UserManager fUserService;
	
	private int fNumber = 0;
	
	/**
	 * Reads the sender's properties file and starts its internal threads.
	 * 
	 * @throws Exception if anything goes wrong
	 */
	public ActivitySender() throws Exception
	{
		fProperties = new HashMap<String, String>();
		fTitleMap = new HashMap<String, String>();
		fVerbMap = new HashMap<String, String>();
		
		//TODO: internationalization
		
		readConfig();

		fProfileName = fProperties.get(PROFILE_NAME);
		fCommentName = fProperties.get(COMMENT_NAME);
		fApprovalName = fProperties.get(APPROVAL_NAME);
        
        //set general parameters
        fShindigUrl = fProperties.get(SHINDIG_URL_PROP);
        fNuxeoUrl = fProperties.get(NUXEO_URL_PROP);
        fOutputFile = fProperties.get(OUTPUT_FILE_PROP);
        
        fLogging = Boolean.parseBoolean(fProperties.get(LOGGING_PROP));
        fIgnoreFolders = Boolean.parseBoolean(fProperties.get(
        		IGNORE_FOLDERS_PROP));
        
        fBlockColls = Boolean.parseBoolean(fProperties.get(
        		BLOCK_COLLS_PROP));
        fBlockFavorites = Boolean.parseBoolean(fProperties.get(
        		BLOCK_FAVORITES_PROP));
        
        fBlockRouteNodes = Boolean.parseBoolean(fProperties.get(
        		BLOCK_RNODES_PROP));
        fBlockTaskDocs = Boolean.parseBoolean(fProperties.get(
        		BLOCK_TASKDOCS_PROP));
        fBlockNonSnapshots = Boolean.parseBoolean(fProperties.get(
        		BLOCK_NONSNAPS_PROP));
        
        boolean downloadUrls = false;
        if("download".equals(fProperties.get(DOC_URL_PROP)))
        {
        	downloadUrls = true;
        }
        fDownloadUrls = downloadUrls;
		
		//initialize logging if configured
		fActLogger = new ActivityLogger(fOutputFile, fLogging);
		if(fLogging)
		{
			
			try
			{
				final PrintWriter writer = new PrintWriter(new BufferedWriter(
					new FileWriter(fOutputFile, true)));
				
				writer.println("initialized activtity stream logger");
				
				writer.println();
				writer.println();
				
				writer.flush();
				writer.close();
			}
			catch(Exception e)
			{
				e.printStackTrace();
			}
		}
        
        fAggregator = new ActivityAggregator(fProperties, this, fActLogger);
        
        try
        {
        	fUserService = Framework.getService(UserManager.class);
        }
        catch(Exception e)
        {
        	e.printStackTrace();
        }
		
		//blacklisted types
		fBlackList = new HashSet<String>();
		fObjectBlackList = new HashSet<String>();
		fillBlacklists();
		
		//create fixed generator activity object
		fGenerator = new JSONObject();
		fGenerator.put("id", fProperties.get(ACT_OBJ_ID_PROP));
		fGenerator.put("displayName", fProperties.get(ACT_OBJ_NAME_PROP));
		fGenerator.put("objectType", fProperties.get(ACT_OBJ_TYPE_PROP));
		fGenerator.put("url", fNuxeoUrl);
	}
	
	private void readConfig()
	{
		final Map<String, String> rawProps = new HashMap<String, String>();
		
		//read general properties file
        final ClassLoader loader = Thread.currentThread()
            .getContextClassLoader();
        ResourceBundle rb = ResourceBundle.getBundle(PROPERTIES,
            Locale.getDefault(), loader);
        
        final int verbsCutoff = "verbs.".length();
        final int titlesCutoff = "titles.".length();
        
        String key = null;
        String value = null;
        
        Enumeration<String> keys = rb.getKeys();
        while(keys.hasMoreElements())
        {
        	key = keys.nextElement();
        	value = rb.getString(key);
        	
        	rawProps.put(key, value);
        }
        
        //read language file and incorporate
        try
        {
            String lang = rawProps.get(LANG_PROP);
            Locale loc = new Locale(lang);
            rb = ResourceBundle.getBundle(LANG_PROPS, loc, loader);
        }
        catch(Exception e)
        {
        	rb = ResourceBundle.getBundle(LANG_PROPS, Locale.getDefault(),
        		loader);
        }
        
        keys = rb.getKeys();
        while(keys.hasMoreElements())
        {
        	key = keys.nextElement();
        	value = rb.getString(key);
        	
        	rawProps.put(key, value);
        }

        //extract properties
        for(Entry<String, String> entry : rawProps.entrySet())
        {
            key = entry.getKey();
            value = entry.getValue();

            //separate verbs and titles
            if(key.startsWith("verbs."))
            {
            	fVerbMap.put(key.substring(verbsCutoff), value);
            }
            else if(key.startsWith("titles."))
            {
            	fTitleMap.put(key.substring(titlesCutoff), value);
            }
            else
            {
                fProperties.put(key, value);
            }
        }
	}
	
	private void fillBlacklists()
	{
		//event type based blacklisting
		String blackListProp = fProperties.get("events.blacklist");
		if(blackListProp.contains(","))
		{
			String[] entries = blackListProp.split(",");
			fBlackList.addAll(Arrays.asList(entries));
		}
		else
		{
			fBlackList.add(blackListProp);
		}
		
		//object type based blacklisting
		//block collections in general if configured
		if(fBlockColls)
		{
			fObjectBlackList.add("Collection");
		}
		
		//block favorites in general if configured
		if(fBlockFavorites)
		{
			fObjectBlackList.add("Favorites");
		}
		
		//block route nodes if configured
		if(fBlockRouteNodes)
		{
			fObjectBlackList.add("RouteNode");
		}
		
		//block task documents if configured
		if(fBlockTaskDocs)
		{
			fObjectBlackList.add("TaskDoc");
		}
		
		//the tag itself being created, not a "Tagging"
		fObjectBlackList.add("Tag");
	}
	
	public JSONObject getGenerator()
	{
		return fGenerator;
	}
	
	public void sendTagAggAct(String docId, String user, List<String> tags, boolean add)
	{
		try
		{
			//activity
			JSONObject activity = new JSONObject();
			
			//user
			JSONObject actor = new JSONObject();
			actor.put("id", user);
			actor.put("displayName", getUserName(user));
			actor.put("objectType", "person");
			activity.put("actor", actor);

			//prepare document
			JSONObject document = getDocumentObjectById(docId);
			if(document == null)
			{
				//abort if document is gone
				return;
			}

			if(tags != null && !tags.isEmpty())
			{
				//tag list available
				JSONObject tagList = new JSONObject();
				
				//list of tags
				String tagString = "";
				//list available
				for(String tag : tags)
				{
					tagString += tag + ", ";
				}
				tagString = tagString.substring(0, tagString.lastIndexOf(","));
				if(tags.size() > 1)
				{
					tagString = "Tags " + "(" + tagString + ")";
				}
				else
				{
					tagString = "Tag " + "\"" + tagString + "\"";
				}
				tagList.put("displayName", tagString);
				tagList.put("objectType", "nuxeoTag");
				activity.put("object", tagList);
				
				//appropriate verb
				if(add)
				{
					activity.put("verb", "add");
				}
				else
				{
					activity.put("verb", "remove");
				}
				
				//add document as target
				activity.put("target", document);
			}
			else
			{
				//generic tagging event
				activity.put("verb", "tag");
				activity.put("object", document);
			}
			
			//send to shindig
			fAggregator.handleActivity(activity, user);
			
			if(fLogging)
			{
				logActivity(activity, user);
			}
		}
		catch(Exception e)
		{
			//TODO?
			fActLogger.logString("Exception: " + e.getLocalizedMessage());
			e.printStackTrace();
		}
	}
	
	private JSONObject getDocumentObjectById(String docId) throws Exception
	{
		CoreSession session = CoreInstance.openCoreSessionSystem("default");
		
		boolean tx = false;
		if(TransactionHelper.isNoTransaction())
		{
			TransactionHelper.startTransaction();
			tx = true;
		}
		DocumentModel model = session.getDocument(new IdRef(docId));
		if(tx)
		{
			TransactionHelper.commitOrRollbackTransaction();
		}

		if(model != null)
		{
			return getDocument(model);
		}
		else
		{
			return null;
		}
	}
	
	private UserManager getUserManager()
	{
		if(fUserService == null)
		{
			try
			{
				fUserService = Framework.getService(UserManager.class);
			}
			catch(Exception e)
			{
				fActLogger.logString("user service unavailable: " + e.getLocalizedMessage());
				e.printStackTrace();
			}
		}
		
		return fUserService;
	}
	
	@Override
	public void handleEvent(final Event event) throws ClientException
	{
		final String eventType = event.getName();
		boolean send = true;
		String user = null;
		
		//check whether an activity should be generated
		try
		{
			user = event.getContext().getPrincipal().getName();
			
			//filter out system events
			if("system".equals(user))
			{
				return;
			}
			
			send = sendingEnabled(event, user);
		}
		catch(Exception e)
		{
			send = false;
			throw new ClientException(e);
		}
		
		//generate activity
		final JSONObject activity = new JSONObject();
		if(send) try
		{
			//verb
			final String verb = getVerb(event);
			activity.put("verb", verb);
			
			//title
			String title = getTitle(event);
			if(title != null)
			{
				activity.put("title", title);
			}
			
			//actor
			final JSONObject actor = new JSONObject();
			actor.put("id", user);
			actor.put("displayName", getUserName(event));
			actor.put("objectType", "person");
			activity.put("actor", actor);
			
			//object
			JSONObject object = null;
			
			//TODO: multiple attached files?
			final DocumentModel model = getPrimaryDocument(event);
			String modelType = null;
			
			if(model != null)
			{
				object = getDocument(model);
				modelType = model.getType();
			}
			
			if(object != null)
			{
				activity.put("object", object);
			}
			
			//special treatment for some types
			if("commentAdded".equals(eventType)
					|| "commentRemoved".equals(eventType))
			{
				//comments
				handleComment(event, activity, object);
			}
			else if("documentTagUpdated".equals(eventType))
			{
				//tagging for non-admins
				//TODO
			}
			else if("Tagging".equals(modelType))
			{
				//tagging
				if("documentCreated".equals(eventType))
				{
					handleTagging(event, activity, object, true);
				}
				//untagging
				else if("aboutToRemove".equals(eventType))
				{
					handleTagging(event, activity, object, false);
				}
			}
			else if("UserProfile".equals(modelType))
			{
				//editing of user profiles
				handleUserProfile(event, activity, object);
			}
			else if("documentWaitingPublication".equals(eventType))
			{
				//request for a document's approval
				handleWaitingApproval(event, activity, object);
			}
			else if(event.getContext().getProperties().containsKey(
				"replacedProxyRefs"))
			{
				//document published in a new section?
				handleSectionOperation(event, activity, object, true);
			}
			else if("aboutToRemove".equals(eventType)
				&& model.getPathAsString().contains("/sections/"))
			{
				//removal from sections
				handleSectionOperation(event, activity, object, false);
			}
			else if("Collection".equals(modelType)
				|| "Favorites".equals(modelType))
			{
				//special collection type
				object.put("objectType", "nuxeoCollection");
				activity.put("object", object);
			}
			
			//generator
			activity.put("generator", fGenerator);
		}
		catch(Exception e)
		{
			send = false;
			
			//log exception if activated
			if(fLogging)
			{
				logException(e);
			}
			
			throw new ClientException(e);
		}
		
		//increment counter
		++fNumber;
		
		//log data if activated
		if(fLogging)
		{
			fActLogger.logEvent(event, fNumber);
		}
		
		//send activity to Shindig server
		if(send)
		{
			//log activity if activated
			if(fLogging)
			{
				logActivity(activity, user);
			}
			
			try
			{
				fAggregator.handleActivity(activity, user);
			}
			catch(ClientException e)
			{
				//log exception if activated
				if(fLogging)
				{
					logException(e);
				}
				
				throw e;
			}
		}
	}
	
	private boolean sendingEnabled(final Event event, String user) throws ClientException
	{
		boolean send = true;
		final String eventType = event.getName();
		if(fBlackList.contains(eventType))
		{
			send = false;
		}
		
		//don't even retrieve document if type is blacklisted
		if(!send)
		{
			return send;
		}

		final DocumentModel model = getPrimaryDocument(event);
		if(model != null)
		{
			final String modelType = model.getType();
			
			//filter out generally blocked types
			if(fObjectBlackList.contains(modelType))
			{
				send = false;
			}
			
			//filter out not yet created documents 
			else if(model.getName() == null)
			{
				send = false;
			}
			
			//block folders
			else if(fIgnoreFolders && model.isFolder())
			{
				send = false;
			}
			
			//block changes that will not be snapshotted
			else if(fBlockNonSnapshots
				&& event.getContext().getProperties().containsKey(
				"CREATE_SNAPSHOT_ON_SAVE"))
			{
				send = false;
			}
			
			//separate documents moved to and from trash
			else if("documentCreated".equals(eventType)
					&& event.getContext()
					.getProperty("versionLabel") != null)
			{
				//filter out "new version created" in favor of
				//"file updated"
				//TODO: may not work every time
				send = false;
			}
			
			//filter out comments added to comments
			else if("commentAdded".equals(eventType)
				&& "Comment".equals(modelType))
			{
				//a duplicate for the document itself should be created
				send = false;
			}
			
			//filter waiting approvals without comments
			else if("documentWaitingPublication".equals(eventType)
				&& event.getContext().getProperty("comment") == null)
			{
				send = false;
			}
			
			//handle aggregation
			else
			{
				send = fAggregator.handleAggregation(event, model, user);
				
				//TODO: actually aggregate and not just block for everything?
			}
		}
		
		return send;
	}
	
	private void handleComment(final Event event, final JSONObject activity,
		JSONObject object) throws Exception
	{
		//document is the new target
		if(object != null)
		{
			activity.remove("object");
			activity.put("target", object);
		}
		
		//construct comment activity object
		object = new JSONObject();
		
		try
		{
			//try retrieving the comment document model
			final DocumentModel comMod = (DocumentModel) event.getContext()
					.getProperties().get("comment_document");
			//TODO: version series ID?
			object.put("id", "nuxeoComment:" + comMod.getId());
			object.put("content", event.getContext().getProperties()
					.get("comment_text"));
		}
		catch(Exception e)
		{
			//comment document not available anymore (deletion)
			fActLogger.logString("comment document unavailable: " + e.getLocalizedMessage());
			e.printStackTrace();
		}
		
		object.put("displayName", fCommentName);
		object.put("objectType", "nuxeoComment");
		
		//generate URL
		DocumentModel docMod =
			(DocumentModel) event.getContext().getArguments()[0];
		String commentUrl = fNuxeoUrl + "nxpath/"
			+ docMod.getRepositoryName() + docMod.getPathAsString()
			+ "@view_documents?tabIds=%3Aview_comments";
		object.put("url", commentUrl);
		
		Serializable comment =
			event.getContext().getProperty("comment_text");
		
		if(comment != null)
		{
			object.put("content", comment.toString());
		}
		
		activity.put("object", object);
	}
	
	private void handleTagging(final Event event, final JSONObject activity,
			JSONObject object, boolean creation) throws Exception
	{
		//generate better tag object
		if(object != null)
		{
			String title = "Tag (" + object.optString("displayName") + ")";
			object.put("displayName", title);
			
			object.put("objectType", "nuxeoTag");
			object.remove("url");
			
			activity.put("object", object);
		}
		
		//get target document by ID
		String targetId = getPrimaryDocument(event).getPart("relation")
			.get("relation:source").getValue().toString();
		DocumentModel tarMod = event.getContext().getCoreSession().getDocument(
				new IdRef(targetId));
		
		JSONObject target = getDocument(tarMod);
		activity.put("target", target);
		
		//set more appropriate verb and title
		if(creation)
		{
			activity.put("verb", "add");
			activity.put("title", fTitleMap.get("titles.tagAdded"));
		}
		else
		{
			activity.put("verb", "remove");
			activity.put("title", fTitleMap.get("titles.tagRemoved"));
		}
	}
	
	private void handleUserProfile(final Event event, final JSONObject activity,
			JSONObject object) throws Exception
	{
		//generate a better profile object
		if(object != null)
		{
			DocumentModel model = getPrimaryDocument(event);
			
			//get person's name
			//TODO: more appropriate source?
			String userId = model.getPart("dublincore")
				.get("dc:creator").getValue().toString();
			String userName = getUserName(userId);
			
			String title = fProfileName + " (" + userName + ")";
			object.put("displayName", title);
			
			//generate proper URL
			String url = fNuxeoUrl + "user/" + userId;
			object.put("url", url);
			
			//TODO: special type?
			
			activity.put("object", object);
		}
	}
	
	private void handleWaitingApproval(final Event event, final JSONObject activity,
			JSONObject object) throws Exception
	{
		//document is the new target
		if(object != null)
		{
			activity.remove("object");
			activity.put("target", object);
		}
		
		//generate approval object
		object = new JSONObject();
		object.put("objectType", "nuxeoApproval");
		object.put("displayName", fApprovalName);
		activity.put("object", object);
		
		//use comment as title (what is waiting where)
		String comment = event.getContext().getProperty("comment").toString();
		activity.remove("title");
		activity.put("title", comment);
	}
	
	private void handleSectionOperation(final Event event, final JSONObject activity,
			JSONObject object, boolean adding) throws Exception
	{
		//special title
		if(adding)
		{
			activity.put("title", fTitleMap.get("addedToSection"));
		}
		else
		{
			activity.put("title", fTitleMap.get("removedFromSection"));
		}
		
		//section as target
		JSONObject target = new JSONObject();
		target.put("objectType", "nuxeoSection");
		
		//extract name and create URL
		DocumentModel model = getPrimaryDocument(event);
		
		//get section path by removing the document from the url
		String sectionPath = model.getPathAsString();
		sectionPath = sectionPath.substring(0, sectionPath.lastIndexOf('/'));
		
		String sectionUrl = fNuxeoUrl + "nxpath/"
			+ model.getRepositoryName() + sectionPath + "/@view_documents";
		target.put("url", sectionUrl);
		
		//extract name from URL
		String name = sectionPath.substring(sectionPath.lastIndexOf('/') + 1);
		target.put("displayName", name);
		
		//add target
		activity.put("target", target);
	}
	
	public DocumentModel getPrimaryDocument(final Event event)
	{
		final Object[] args = event.getContext().getArguments();
		DocumentModel model = null;
		
		//TODO: multiple attached files?
		if(args.length > 0 && args[0] != null)
		{
			if(args[0] instanceof DocumentModel)
			{
				model = (DocumentModel)args[0];
			}
		}
		
		return model;
	}
	
	private String getUserName(final Event event) throws ClientException
	{
		String name = event.getContext().getPrincipal().getName();
		
		return getUserName(name);
	}
	
	public String getUserName(String userId) throws ClientException
	{
		try
		{
			final NuxeoPrincipal principal =
					getUserManager().getPrincipal(userId);
			
			//TODO: evaluate
			
			if(principal != null)
			{
				String firstName = principal.getFirstName();
				String lastName = principal.getLastName();
				
				if(firstName != null)
				{
					if(lastName != null)
					{
						userId = firstName + " " + lastName;
					}
					else
					{
						userId = firstName;
					}
				}
				else if(lastName != null)
				{
					userId = lastName;
				}
			}
			
		}
		catch(Exception e)
		{
			//TODO: activate for production
			throw new ClientException(e);
//			e.printStackTrace();
		}
		
		return userId;
	}
	
	private String getVerb(final Event event)
	{
		final DocumentModel model = getPrimaryDocument(event);
		final String type = event.getName();
		
		String verb = fVerbMap.get(type);
		if(verb == null)
		{
			verb = "post";
		}
		
		if(model != null)
		{
			if("documentMoved".equals(type))
			{
				if(model.getName() != null
						&& model.getName().endsWith(".trashed"))
				{
					verb = "remove";
				}
				else if("deleted".equals(event.getContext().getProperty(
						"documentLifeCycle")))
				{
					verb = "update";
				}
			}
		}
		
		return verb;
	}
	
	private String getTitle(final Event event)
	{
		final DocumentModel model = getPrimaryDocument(event);
		final String type = event.getName();
		
		String title = fTitleMap.get(type);
		
		if(model != null)
		{
			if("documentMoved".equals(type))
			{
				if(model.getName() != null
						&& model.getName().endsWith(".trashed"))
				{
					if(type.equals("documentMoved"))
					{
						title = fTitleMap.get(DOC_TO_TRASH);
					}
//						else if(type.equals("aboutToRemoveVersion"))
//						{
//							//TODO: how to differentiate?
//							title = fTitleMap.get(DOC_VERSION_TO_TRASH);
//						}
				}
				else if("deleted".equals(event.getContext().getProperty(
						"documentLifeCycle")))
				{
					title = fTitleMap.get("documentRestored");
				}
			}
		}
		
		return title;
	}
	
	public JSONObject getDocument(final DocumentModel model) throws ClientException
	{
		final JSONObject object = new JSONObject();
		
		try
		{
//			object.put("objectType", "nuxeoDocument:" + model.getType());

			//TODO: possible collisions due to unspecific object type?
			String type = model.getType();

			/*
			 * replace default type with corresponding value from
			 * ActivityStreams 2.0
			 */
			if("File".equals(type))
			{
				type = "Document";
			}

			object.put("objectType", type);

			object.put("displayName", model.getTitle());

			//TODO: use normal ID instead? -> makes tracking nearly
			//      impossible
			//TODO: avoid collisions by prefixing IDs?
			object.put("id", model.getVersionSeriesId());
			object.put("content", "type: " + model.getType()
					+ "\nname: " + model.getName());
			
			
			if(!fDownloadUrls)
			{
				//construct repository link
				//TODO: not for deleted documents
				String documentUrl = fNuxeoUrl + "nxpath/"
						+ model.getRepositoryName()
						+ model.getPathAsString()
						+ "/@view_documents";
				object.put("url", documentUrl);
			}
			else
			{
				//construct download link
				//TODO: not for deleted documents
				String downloadUrl = fNuxeoUrl + "nxfile/"
				+ model.getRepositoryName() + "/" + model.getId()
				+ "/blobholder:0/" + model.getTitle();
				object.put("url", downloadUrl);
			}
			
			//TODO: additional data based on action
		}
		catch(Exception e)
		{
			//TODO: better solution
			e.printStackTrace();
			
			//TODO: check if that breaks anything
			throw new ClientException(e);
		}
		
		return object;
	}
	
	public void logMessage(String message)
	{
		try
		{
			final PrintWriter writer = new PrintWriter(new BufferedWriter(
					new FileWriter(fOutputFile, true)));
			
			writer.println(message);
			
			writer.println();
			writer.println();
			
			writer.flush();
			writer.close();
		}
		catch(Exception e)
		{
			throw new ClientException(e);
		}
	}
	
	public void logActivity(final JSONObject activity, String user)
			throws ClientException
	{
		try
		{
			//log activities being sent to file
			final PrintWriter writer = new PrintWriter(new BufferedWriter(
					new FileWriter(fOutputFile, true)));
			
			writer.println("activity " + fNumber + ":");
			writer.println(activity);
			
			writer.println();
			writer.println();
			
			writer.flush();
			writer.close();
		}
		catch(Exception e)
		{
			throw new ClientException(e);
		}
	}
	
	public void logException(Exception exception) throws ClientException
	{
		try
		{
			//log activities being sent to file
			final PrintWriter writer = new PrintWriter(new BufferedWriter(
					new FileWriter(fOutputFile, true)));
			
			writer.println("Exception: " + exception.getMessage());
			writer.println("stack trace: ");
			for(StackTraceElement element : exception.getStackTrace())
			{
				writer.println(element);
			}
			
			writer.println();
			writer.println();
			
			writer.flush();
			writer.close();
		}
		catch(Exception e)
		{
			throw new ClientException(e);
		}
	}

	public void send(final JSONObject activity, String user)
			throws ClientException
	{
		final String json = activity.toString();
		
		try
		{
			URL shindigUrl = new URL(fShindigUrl + ACT_STR_FRAG + user +
	                "/@self");
            final HttpURLConnection connection =
                (HttpURLConnection) shindigUrl.openConnection();
            
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", String.valueOf(
                json.length()));
            
            OutputStreamWriter writer = new OutputStreamWriter(
                connection.getOutputStream(), "UTF-8");
            writer.write(json);
            writer.flush();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream()));
            
            String line = reader.readLine();
            while(line != null)
            {
                line = reader.readLine();
            }
            //TODO: evaluate answer?
            
            reader.close();
		}
		catch(Exception e)
		{
			//TODO: activate for production
			throw new ClientException(e);
//			e.printStackTrace();
		}
	}
}

package de.hofuniversity.iisys.nuxeo.activitystreams;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.security.Principal;
import java.util.Map;
import java.util.Map.Entry;

import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.model.DocumentPart;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.user.center.profile.UserProfileService;
import org.nuxeo.runtime.api.Framework;

public class ActivityLogger
{
	private final String fOutputFile;
	private final boolean fActive;
	
	public ActivityLogger(String outputFile, boolean active)
	{
		fOutputFile = outputFile;
		fActive = active;
	}
	
	public void logString(String msg) throws ClientException
	{
		if(!fActive)
		{
			return;
		}
		
		try
		{
			final PrintWriter writer = new PrintWriter(new BufferedWriter(
				new FileWriter(fOutputFile, true)));

			writer.println(msg);
			
			writer.println();
			writer.println();
			
			writer.flush();
			writer.close();
		}
		catch(Exception e)
		{
			//TODO: activate for production
			throw new ClientException(e);
//			e.printStackTrace();
		}
	}
	
	public void logEvent(final Event event, int number) throws ClientException
	{
		if(!fActive)
		{
			return;
		}
		
		try
		{
			final PrintWriter writer = new PrintWriter(new BufferedWriter(
				new FileWriter(fOutputFile, true)));
			
			writer.println("event " + number + ":");
			writer.println("class: " + event.getClass().getName());
			
			writer.println("\tname: " + event.getName());
			writer.println("\trollbackMessage: " + event.getRollbackMessage());

			//TODO: method does not exist anymore
//			writer.println("\trollbackException: "
//					+ event.getRollbackException());
			
			writer.println("\ttime: " + event.getTime());
			writer.println("\ttoString: " + event.toString());
			
			writer.println("\tisBubbleException: "
					+ event.isBubbleException());
			writer.println("\tisCanceled: "
					+ event.isCanceled());
			writer.println("\tisCommitEvent: "
					+ event.isCommitEvent());
			writer.println("\tisImmediate: "
					+ event.isImmediate());
			writer.println("\tisInline: "
					+ event.isInline());
			writer.println("\tisLocal: "
					+ event.isLocal());
			writer.println("\tisMarkedForRollback: "
					+ event.isMarkedForRollBack());
			writer.println("\tisPublic: "
					+ event.isPublic());
			
			
			//evaluate flags
			final int flags = event.getFlags();
			writer.println("\tflags: " + flags);
			writer.println("\t\tbubbleException: "
					+ (flags & Event.FLAG_BUBBLE_EXCEPTION));
			writer.println("\t\tcancel: "
					+ (flags & Event.FLAG_CANCEL));
			writer.println("\t\tcommit :"
					+ (flags & Event.FLAG_COMMIT));
			writer.println("\t\timmediate: "
					+ (flags & Event.FLAG_IMMEDIATE));
			writer.println("\t\tinline: "
					+ (flags & Event.FLAG_INLINE));
			writer.println("\t\tlocal: "
					+ (flags & Event.FLAG_LOCAL));
			writer.println("\t\trollback: "
					+ (flags & Event.FLAG_ROLLBACK));
			
			//print context
			final EventContext context = event.getContext();
			writer.println("\tcontext: " + context.toString());
			writer.println("\t\trepositoryName: "
					+ context.getRepositoryName());
			
			//arguments
			final Object[] arguments = context.getArguments();
			writer.println("\t\targuments: " + arguments);
			for(Object arg : arguments)
			{
				if(arg == null)
				{
					writer.println("\t\t\tnull argument");
				}
				else
				{
					writer.println("\t\t\tclass: " + arg.getClass());
					writer.println("\t\t\ttoString: " + arg.toString());
					
					if(arg instanceof DocumentModel)
					{
						final DocumentModel model = (DocumentModel)arg;
						
						if(model.getName() == null
								|| "null".equals(model.getName()))
						{
							writer.println("\t\t\tmodel was null");
							continue;
						}
						
						writer.println("\t\t\tcheckinComment: "
							+ model.getCheckinComment());
						writer.println("\t\t\tcurrentLifeCycleState: "
								+ model.getCurrentLifeCycleState());
						writer.println("\t\t\tId: " + model.getId());
						writer.println("\t\t\tlifeCyclePolicy: "
								+ model.getLifeCyclePolicy());
						writer.println("\t\t\tname: " + model.getName());
						writer.println("\t\t\tpathAsString: "
								+ model.getPathAsString());
						writer.println("\t\t\trepositoryName: "
								+ model.getRepositoryName());
						writer.println("\t\t\tsessionId: "
								+ model.getSessionId());
						writer.println("\t\t\tsourceId: "
								+ model.getSourceId());
						writer.println("\t\t\ttitle: " + model.getTitle());
						writer.println("\t\t\ttype: " + model.getType());
						writer.println("\t\t\tversionLabel: "
								+ model.getVersionLabel());
						writer.println("\t\t\tversionSeriesId: "
								+ model.getVersionSeriesId());
						
						writer.println("\t\t\tACP: " + model.getACP());
						writer.println("\t\t\tdocumentType: "
								+ model.getDocumentType());
						
						writer.println("\t\t\tdocumentParts:");
						
						for(DocumentPart part : model.getParts())
						{
							writer.println("\t\t\t\tname: " + part.getName());
							writer.println("\t\t\t\tvalue: " + part.getValue());
							
							//children
//							writer.println("\t\t\tchildren: "
//									+ part.getChildren());
							//TODO
						}
					}
					
					writer.println();
				}
			}
			
			final CoreSession coreSession = context.getCoreSession();
			writer.println("\t\tcoreSession: " + coreSession);
			
			final Principal principal = context.getPrincipal();
			writer.println("\t\tprincipal: " + principal.toString());
			writer.println("\t\t\tname: " + principal.getName());
			
			Map<String, Serializable> props = context.getProperties();
			writer.println("\t\tproperties: ");
			for(Entry<String, Serializable> propE : props.entrySet())
			{
				writer.println("\t\t\t" + propE.getKey() + ": "
						+ propE.getValue());
			}
			
			//log user information
			writer.println();
			DocumentModel profile = null;
			
			//TODO: causes transaction problems
//			if(Framework.getService(UserProfileService.class) != null)
//			{
//				profile = Framework.getService(UserProfileService.class).getUserProfileDocument(
//						event.getContext().getCoreSession());
//			}
//			else
//			{
//				writer.println("no profile service");
//			}
			
			if(profile != null)
			{
				writer.println("\tuserProfileDocument: " + profile.toString());
				
				writer.println("\t\tcheckinComment: "
						+ profile.getCheckinComment());
				writer.println("\t\tID: " + profile.getId());
				writer.println("\t\tname: " + profile.getName());
				writer.println("\t\tpathAsString: "
						+ profile.getPathAsString());
				writer.println("\t\trepositoryName: "
						+ profile.getRepositoryName());
				writer.println("\t\ttitle: " + profile.getTitle());
				writer.println("\t\ttype: " + profile.getType());
				writer.println("\t\tversionLabel: "
						+ profile.getVersionLabel());
				
				writer.println("\t\tdocumentType: "
						+ profile.getDocumentType());
				
				//TODO: properties?
				
				//binary full text
				
				if(profile.getBinaryFulltext() != null)
				{
					writer.println("\t\tbinaryFulltext:");

					for(Entry<String, String> textE
							: profile.getBinaryFulltext().entrySet())
					{
						writer.println("\t\t\t" + textE.getKey() + ": "
								+ textE.getValue());
					}
				}
				
				if(profile.getParts() != null)
				{
					writer.println("\t\tdocumentParts:");
					
					for(DocumentPart part : profile.getParts())
					{
						writer.println("\t\t\tname: " + part.getName());
						writer.println("\t\t\tvalue: " + part.getValue());
						
						//children
//						writer.println("\t\t\tchildren: "
//								+ part.getChildren());
						//TODO
					}
				}
			}
			
			writer.println();
			writer.println();
			
			writer.flush();
			writer.close();
		}
		catch(Exception e)
		{
			//TODO: activate for production
			throw new ClientException(e);
//			e.printStackTrace();
		}
	}
}

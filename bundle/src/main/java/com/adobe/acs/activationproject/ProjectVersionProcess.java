package com.adobe.acs.activationproject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;

import com.adobe.acs.activationproject.impl.projects.ProjectContentExtractor;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Revision;
import com.day.cq.wcm.api.WCMException;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.metadata.MetaDataMap;

/*
 * Some of this code may seem redundant at first glance, until you realize that AEM handles asset and page versioning through separate APIs.
 */

@Component
@Service(WorkflowProcess.class)
@Property(name = "process.label", value = "Version Project")
public class ProjectVersionProcess implements WorkflowProcess {

	@Reference
	private ResourceResolverFactory resolverFactory;
	
	/*
	 * @see com.day.cq.workflow.exec.WorkflowProcess#execute(com.day.cq.workflow.exec.WorkItem, com.day.cq.workflow.WorkflowSession, com.day.cq.workflow.metadata.MetaDataMap)
	 * The execute method is the entry point for a WorkflowProcess.  When the workflow is executed and this step is reached, execute will be called.
	 */
	public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args) throws WorkflowException {
		
		ResourceResolver resolver = null;

		try {	
			Session session = workflowSession.getSession();
			//A resource resolver is needed so that we can adapt it to a PageManager or AssetManager
			resolver = resolverFactory.getResourceResolver(Collections.singletonMap("user.jcr.session", (Object)session));
			String path = (String) workItem.getWorkflowData().getPayload();
			
			Node versionParentNode = createVersionParentNode(session, path);		
			Map<String, List<String>> projectContent = new ProjectContentExtractor().getProjectContent(session, path);
			
			versionPages(resolver, versionParentNode, projectContent);		
			versionAssets(resolver, versionParentNode, projectContent);
			
			session.save();			
		} catch (Exception e) {
			throw new WorkflowException(e);
		} finally {
			if (resolver.isLive()) {
				resolver.close();
			}
		}
	}

	/*
	 * Creates a version node in the project under which to store version information about each asset and page.
	 * Note that this is not the same as OOTB revisions.  
	 * Instead, we are storing information about the state of the in-place OOTB revisions at this point in time. 
	 */
	private Node createVersionParentNode(Session session, String path) throws RepositoryException {
		Node damProjectNode = session.getNode(path);
		Value[] projectPathValues = damProjectNode.getProperty("projectPath").getValues();
		String projectPath = projectPathValues[0].getString();
		Node contentNode = session.getNode(projectPath + "/jcr:content");
		Node versionsParent = JcrUtils.getOrAddNode(contentNode, "versions");
		return versionsParent.addNode(getDateString());
	}
	
	private String getDateString() {
		Calendar today = Calendar.getInstance();
		DateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS");
		return format.format(today.getTime());
	}
	
	private void versionPages(ResourceResolver resolver, Node versionNode, Map<String, List<String>> projectContent) throws RepositoryException, WCMException {
		List<String> pagePaths = projectContent.get(ProjectContentExtractor.PAGE_CONTENT);
		for (String pagePath : pagePaths) {
			addPageVersionNode(resolver, versionNode, pagePath);
		}
	}
	
	/*TODO: This method shares a lot of similar code with addAssetVersionNode and should be refactored to eliminate duplication.  
	 * This would probably require the creation of some sort of mappers that extend a common base class.
	 */
	private void addPageVersionNode(ResourceResolver resolver, Node versionNode, String pagePath) throws RepositoryException, WCMException {
		PageManager pageManager = resolver.adaptTo(PageManager.class);
		
		String version = null;
		Boolean activated = false;
		
		Page currentPage = pageManager.getPage(pagePath);
		ValueMap currPageProps = currentPage.getProperties();
		
		if (isPageActivated(currPageProps)) {
			activated = true;
			version = getPageVersion(pagePath, pageManager, currPageProps);
		}

		//Map relevant properties to the page version node.
		Node contentVersionNode = versionNode.addNode(currentPage.getTitle() + "-" + getDateString());
		contentVersionNode.setProperty("type", ProjectContentExtractor.PAGE_CONTENT);
		contentVersionNode.setProperty("path", pagePath);
		contentVersionNode.setProperty("activated", activated.toString());		
		contentVersionNode.setProperty("versionId", version);		
	}

	private boolean isPageActivated(ValueMap currPageProps) {
		return currPageProps.keySet().contains("cq:lastReplicationAction") && currPageProps.get("cq:lastReplicationAction", String.class).equals("Activate");
	}
	
	private String getPageVersion(String pagePath, PageManager pageManager, ValueMap currPageProps) throws WCMException {
		Calendar lastReplDate = currPageProps.get("cq:lastReplicated", Calendar.class);
		Collection<Revision> revisions = pageManager.getRevisions(pagePath, lastReplDate);
		if (revisions.size() > 0) {
			Revision currentlyPublishedRevision = Collections.max(revisions, new PageRevisionComparator());
			return currentlyPublishedRevision.getLabel();
		} else {
			return null;
		}
		
	}
	
	private void versionAssets(ResourceResolver resolver, Node versionParentNode, Map<String, List<String>> projectContent) throws Exception {
		List<String> assetPaths = projectContent.get(ProjectContentExtractor.ASSET_CONTENT);
		for (String assetPath : assetPaths) {
			addAssetVersionNode(resolver, versionParentNode, assetPath);
		}
	}
	
	private void addAssetVersionNode(ResourceResolver resolver, Node versionNode, String assetPath) throws Exception {
		Session session = resolver.adaptTo(Session.class);
		AssetManager assetManager = resolver.adaptTo(AssetManager.class);
		
		String version = null;
		Boolean activated = false;
		
		Node assetContentNode = session.getNode(assetPath + "/jcr:content");
		
		if (isAssetActivated(assetContentNode)) {
			activated = true;
			version = getAssetVersion(assetPath, assetManager, assetContentNode);
		}
		
		Node contentVersionNode = versionNode.addNode(assetPath.substring(assetPath.lastIndexOf("/") + 1) + "-" + getDateString());
		contentVersionNode.setProperty("type", ProjectContentExtractor.ASSET_CONTENT);
		contentVersionNode.setProperty("path", assetPath);
		contentVersionNode.setProperty("activated", activated.toString());	
		contentVersionNode.setProperty("versionId", version);	
	}

	private boolean isAssetActivated(Node assetContentNode) throws RepositoryException, ValueFormatException, PathNotFoundException {
		return assetContentNode.hasProperty("cq:lastReplicationAction") && assetContentNode.getProperty("cq:lastReplicationAction").getString().equals("Activate");
	}
	
	private String getAssetVersion(String assetPath, AssetManager assetManager, Node assetContentNode) throws ValueFormatException, RepositoryException, PathNotFoundException, Exception {
		String version = null;
		
		Calendar lastReplicated = assetContentNode.getProperty("cq:lastReplicated").getDate();
		Collection<com.day.cq.dam.api.Revision> revisions = assetManager.getRevisions(assetPath, lastReplicated);
		if (revisions.size() > 0) {
			com.day.cq.dam.api.Revision currentlyPublishedRevision = Collections.max(revisions, new AssetRevisionComparator());
			version = currentlyPublishedRevision.getLabel();
		}
		
		return version;
	}
}

/*
 * Sort page revisions by created date.
 */
class PageRevisionComparator implements Comparator<Revision> {
	
	public int compare(Revision o1, Revision o2) {
		if (o1.getCreated().getTimeInMillis() < o2.getCreated().getTimeInMillis()) {
			return -1;
		} else if (o1.getCreated().getTimeInMillis() > o2.getCreated().getTimeInMillis()) {
			return 1;
		} else {
			return 0;
		}
	}
}

/*
 * Sort asset revisions by created date.
 */
class AssetRevisionComparator implements Comparator<com.day.cq.dam.api.Revision> {	
	
	public int compare(com.day.cq.dam.api.Revision o1, com.day.cq.dam.api.Revision o2) {
		if (o1.getCreated().getTimeInMillis() < o2.getCreated().getTimeInMillis()) {
			return -1;
		} else if (o1.getCreated().getTimeInMillis() > o2.getCreated().getTimeInMillis()) {
			return 1;
		} else {
			return 0;
		}
	}
}
package com.adobe.acs.activationproject.impl.projects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
 
public class ProjectContentExtractor {
	
	public static final String PAGE_CONTENT = "pages";
	public static final String ASSET_CONTENT = "assets";
	

	public Map<String, List<String>> getProjectContent(Session session, String path) throws RepositoryException {

		//Resolve project path.  The path included in the workflow payload is from /content/dam/projects, but all of the useful content is in /content/projects.
		Node damProjectNode = session.getNode(path);
		Value[] projectPathValues = damProjectNode.getProperty("projectPath").getValues();
		String projectPath = projectPathValues[0].getString();

		List<String> pagePaths = extractPagePaths(session, projectPath);
		List<String> assetPaths = extractAssetPaths(session, projectPath);
		
		Map<String, List<String>> projectContent = new HashMap<String, List<String>>();
		projectContent.put(PAGE_CONTENT, pagePaths);
		projectContent.put(ASSET_CONTENT, assetPaths);
		
		return projectContent;
	}

	private List<String> extractPagePaths(Session session, String projectPath) throws RepositoryException, PathNotFoundException, ValueFormatException {
		List<String> pagePaths = new ArrayList<String>();
		if (session.nodeExists(projectPath + "/jcr:content/dashboard/gadgets/channels")) {			
			Node pagesParentNode = session.getNode(projectPath + "/jcr:content/dashboard/gadgets/channels");    	
			NodeIterator pageNodes = pagesParentNode.getNodes();    	
			while (pageNodes.hasNext()) {
				Node pageNode = pageNodes.nextNode();
				String pagePath = pageNode.getProperty("suffix").getString();
				pagePaths.add(pagePath);
			}
		}
		return pagePaths;
	}
	
	private List<String> extractAssetPaths(Session session, String projectPath) throws RepositoryException, PathNotFoundException, ValueFormatException {
		List<String> assetPaths = new ArrayList<String>();
		if (session.nodeExists(projectPath + "/jcr:content/dashboard/gadgets/assetcollection")) {
			Node collectionsGadgetNode = session.getNode(projectPath + "/jcr:content/dashboard/gadgets/assetcollection");			
			if (collectionsGadgetNode.hasProperty("collectionPath")) {
				String collectionPath = collectionsGadgetNode.getProperty("collectionPath").getString();
				Node collectionMembersNode = session.getNode(collectionPath + "/sling:members");
				Value[] assetPathValueArray = collectionMembersNode.getProperty("sling:resources").getValues();
				for (Value value : assetPathValueArray) {
					assetPaths.add(value.getString());
				}
			}
		}
		return assetPaths;
	}
}

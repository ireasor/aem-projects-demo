/*
 * Copyright 1997-2008 Day Management AG
 * Barfuesserplatz 6, 4001 Basel, Switzerland
 * All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * Day Management AG, ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Day.
 */
package com.adobe.acs.activationproject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.jcr.resource.JcrResourceResolverFactory;

import com.day.cq.dam.api.Asset;
import com.day.cq.wcm.api.Revision;
import com.day.cq.wcm.api.WCMException;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.job.AbsoluteTimeoutHandler;
import com.day.cq.workflow.metadata.MetaDataMap;

@Component(metatype = false)
@Service
@Property(name = "process.label", value = "DAM Create Asset Version")
public class DAMCreateAssetVersion implements WorkflowProcess {

	@Reference
	private JcrResourceResolverFactory jcrResolverFactory = null;

	/*
	 * @see com.day.cq.workflow.exec.WorkflowProcess#execute(com.day.cq.workflow.exec.WorkItem, com.day.cq.workflow.WorkflowSession, com.day.cq.workflow.metadata.MetaDataMap)
	 * The execute method is the entry point for a WorkflowProcess.  When the workflow is executed and this step is reached, execute will be called.
	 */
	public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args) throws WorkflowException {
		ResourceResolver resolver = null;
		
		try {
			Session session = workflowSession.getSession();
			//Get a resource resolver based on the session.  This is required so that we can retrieve a resource and adapt it to an Asset.
			resolver = jcrResolverFactory.getResourceResolver(session);
			WorkflowData data = workItem.getWorkflowData();
			
			Asset asset = getAssetFromPayload(session, resolver, data);
			
			final String versionLabel = createUniqueVersionLabel(asset.getRevisions(null), getAbsTime(workItem));
			com.day.cq.dam.api.Revision rev = asset.createRevision(versionLabel, null);
			String revStr = getAssetRevision(rev, session);

			updateWorkflowMetadata(workItem, workflowSession, data, revStr);
			
		} catch (RepositoryException e) {
			throw new WorkflowException(e);
		} catch (WCMException e) {
			throw new WorkflowException(e);
		} catch (Exception e) {
			throw new WorkflowException(e);
		} finally {
			//Since we got a resource resolver from the factory, it is our responsibility to close it.  This does not close the workflow session.
			if (resolver.isLive()) {
				resolver.close();
			}
		}
	}
	
	/*
	 * The payload used in the DAM Update Asset workflow is the original rendition.  
	 * See the Launcher for the DAM Update Asset workflow for more info.
	 * In order to create an asset version, we need the actual Asset node itself, though.
	 */
	private Asset getAssetFromPayload(Session session, ResourceResolver resolver, WorkflowData data) throws RepositoryException {		
		Node renditionNode = session.getNode((String) data.getPayload());
		Node assetNode = renditionNode.getParent().getParent().getParent();
		return resolver.getResource(assetNode.getPath()).adaptTo(Asset.class);
	}

	/*
	 * Increment the version number.
	 */
	private String createUniqueVersionLabel(Collection<?> revisions, final String versionLabelHint) throws RepositoryException {

		if (versionLabelHint==null) {
			return null;
		}

		final List<Version> versions = new LinkedList<Version>();
		for (final Object o : revisions) {

			final Version v;
			if (o instanceof Revision) {
				v = ((Revision)o).getVersion();
			} else if (o instanceof com.day.cq.dam.api.Revision) {
				v = ((com.day.cq.dam.api.Revision)o).getVersion();
			} else {
				v = null;
			}

			if (null != v) {
				versions.add(v);
			}
		}

		String versionLabel = versionLabelHint;

		int count = 1;
		while (true) {

			boolean unique = true;
			for (final Version v : versions) {
				if (v.getContainingHistory().hasVersionLabel(versionLabel)) {
					versionLabel = versionLabelHint + " (" + ++count + ")";
					unique = false;
					break;
				}
			}

			if (unique) {
				break;
			}
		}

		return versionLabel;
	}

	private String getAssetRevision(com.day.cq.dam.api.Revision revision, Session session) throws RepositoryException {
		String vid = revision.getId();
		Node v = session.getNodeByIdentifier(vid);
		return v.getName();
	}

	private String getAbsTime(WorkItem workItem) {
		if (workItem.getWorkflowData().getMetaDataMap().get(AbsoluteTimeoutHandler.ABS_TIME, String.class) != null) {
			Calendar cal = getTime(workItem);
			SimpleDateFormat formatter = new SimpleDateFormat("yyyy.MM.dd HH.mm.ss");
			return "Scheduled Activation Time is " + formatter.format(cal.getTime());
		} else {
			return null;
		}
	}

	private Calendar getTime(WorkItem workItem) {
		Long time = workItem.getWorkflowData().getMetaDataMap().get(AbsoluteTimeoutHandler.ABS_TIME, Long.class);
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(time);
		return cal;
	}

	// set version info in workflow data's metadata
	private void updateWorkflowMetadata(WorkItem workItem, WorkflowSession workflowSession, WorkflowData data, String revStr) {
		if (revStr != null) {
			data.getMetaDataMap().put("resourceVersion", revStr);
			if (workItem.getWorkflowData().getMetaDataMap().get(AbsoluteTimeoutHandler.ABS_TIME, String.class) != null) {
				Calendar cal = getTime(workItem);
				data.getMetaDataMap().put("comment", "Activate version " + revStr + " on " + cal.getTime().toString());
			}
			workflowSession.updateWorkflowData(workItem.getWorkflow(), data);
		}
	}
}
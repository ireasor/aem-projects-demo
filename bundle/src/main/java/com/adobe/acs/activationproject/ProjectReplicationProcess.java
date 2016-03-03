package com.adobe.acs.activationproject;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.acs.activationproject.impl.projects.ProjectContentExtractor;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.Replicator;
import com.day.cq.wcm.workflow.api.WcmWorkflowService;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.metadata.MetaDataMap;

@Component
@Service(WorkflowProcess.class)
@Property(name = "process.label", value = "Activate Project")
public class ProjectReplicationProcess implements WorkflowProcess {

	private static final Logger log = LoggerFactory.getLogger(ProjectReplicationProcess.class);

	@Reference
	protected Replicator replicator;

	@Reference
	protected EventAdmin eventAdmin;

	/*
	 * @see com.day.cq.workflow.exec.WorkflowProcess#execute(com.day.cq.workflow.exec.WorkItem, com.day.cq.workflow.WorkflowSession, com.day.cq.workflow.metadata.MetaDataMap)
	 * The execute method is the entry point for a WorkflowProcess.  When the workflow is executed and this step is reached, execute will be called.
	 */
	public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args) throws WorkflowException {

		try {
			activateProject(workItem, workflowSession.getSession());
		} catch (Exception e) {
			throw new WorkflowException(e);
		}
	}

	private void activateProject(WorkItem workItem, Session session) throws Exception{
		String projectPath = (String) workItem.getWorkflowData().getPayload();
		List<String> contentPaths = getContentPaths(session, projectPath);

		for (String contentPath : contentPaths) {
			if (canReplicate(session, contentPath)) {
				replicator.replicate(session, ReplicationActionType.ACTIVATE, contentPath);
			} else {
				requestActivation(session, contentPath);
			}
		}
	}

	private List<String> getContentPaths(Session session, String projectPath) throws RepositoryException {
		List<String> paths = new ArrayList<String>();
		ProjectContentExtractor extractor = new ProjectContentExtractor();
		Map<String, List<String>> projectContent = extractor.getProjectContent(session, projectPath);
		paths.addAll(projectContent.get(ProjectContentExtractor.PAGE_CONTENT));
		paths.addAll(projectContent.get(ProjectContentExtractor.ASSET_CONTENT));
		return paths;
	}

	protected boolean canReplicate(Session session, String path) throws AccessDeniedException {
		try {
			AccessControlManager acMgr = session.getAccessControlManager();
			return acMgr.hasPrivileges(path, new Privilege[]{acMgr.privilegeFromName(Replicator.REPLICATE_PRIVILEGE)});
		} catch (RepositoryException e) {
			return false;
		}
	}

	/*
	 * Send an event for an activation so that a request for activation workflow can be run.
	 */
	private void requestActivation(Session session, String path) {
		log.debug(session.getUserID() + " is not allowed to replicate " + path + ". Issuing request for replication");

		final Dictionary<String, Object> properties = new Hashtable<String, Object>();
		properties.put("path", path);
		properties.put("replicationType", ReplicationActionType.ACTIVATE);
		properties.put("userId", session.getUserID());

		Event event = new Event(WcmWorkflowService.EVENT_TOPIC, properties);
		eventAdmin.sendEvent(event);
	}
}

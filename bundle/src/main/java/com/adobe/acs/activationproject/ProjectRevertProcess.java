package com.adobe.acs.activationproject;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.event.EventAdmin;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.metadata.MetaDataMap;

@Component
@Service(WorkflowProcess.class)
@Property(name = "process.label", value = "Revert Project")
public class ProjectRevertProcess implements WorkflowProcess {

	@Reference
	protected Replicator replicator;

	@Reference
	protected EventAdmin eventAdmin;

	/*
	 * @see com.day.cq.workflow.exec.WorkflowProcess#execute(com.day.cq.workflow.exec.WorkItem, com.day.cq.workflow.WorkflowSession, com.day.cq.workflow.metadata.MetaDataMap)
	 * The execute method is the entry point for a WorkflowProcess.  When the workflow is executed and this step is reached, execute will be called.
	 */
	public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args) throws WorkflowException {

		Session session = workflowSession.getSession();

		try {
			Node latestVersion = getLatestProjectVersion(workItem, session);
			NodeIterator contentVersions = latestVersion.getNodes();

			while (contentVersions.hasNext()) {
				Node contentNode = contentVersions.nextNode();

				if (contentNode.getProperty("activated").getString().equals("true")) {
					if (contentNode.hasProperty("versionId")) {
						String versionId = contentNode.getProperty("versionId").getString();
						ReplicationOptions opts = new ReplicationOptions();
						opts.setRevision(versionId);
						replicator.replicate(session, ReplicationActionType.ACTIVATE, contentNode.getProperty("path").getString(), opts);
					}
				} else {
					replicator.replicate(session, ReplicationActionType.DEACTIVATE, contentNode.getProperty("path").getString());
				}
			}

		} catch (RepositoryException e) {
			throw new WorkflowException(e);
		} catch (ReplicationException e) {
			throw new WorkflowException(e);
		}
	}

	private Node getLatestProjectVersion(WorkItem workItem, Session session) throws PathNotFoundException, RepositoryException, ValueFormatException {
		WorkflowData data = workItem.getWorkflowData();
		String path = (String) data.getPayload();

		//Project node under /content/dam/projects
		Node damProjectNode = session.getNode(path);
		Value[] projectPathValues = damProjectNode.getProperty("projectPath").getValues();
		
		//Project node under /content/projects
		String projectPath = projectPathValues[0].getString();
		
		Node versionsNode = session.getNode(projectPath + "/jcr:content/versions");
		Node latestVersion = getLatestVersion(versionsNode);
		return latestVersion;
	}

	private Node getLatestVersion(Node versionsNode) throws RepositoryException {
		NodeIterator iterator = versionsNode.getNodes();
		Node lastNode = null;

		while (iterator.hasNext()) {
			lastNode = iterator.nextNode();
		}

		return lastNode;
	}
}

package com.adobe.acs.activationproject;

import java.text.SimpleDateFormat;
import java.util.Calendar;

import javax.jcr.Value;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;

import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.job.AbsoluteTimeoutHandler;
import com.day.cq.workflow.timeout.autoadvance.AbsoluteTimeAutoAdvancer;

/*
 * An extension of the OOTB AbsoluteTimeAutoAdvancer.  Since a project stores its metadata in a different location and format, we need to override getTimeoutDate.
 */

@Component(metatype = false)
@Service(value={WorkflowProcess.class, AbsoluteTimeoutHandler.class})
@Properties({ 
	@Property(name = "service.description", value = "Project Workflow AutoAdvancer"),
	@Property(name = "process.label", value = "Project Absolute Time Auto Advancer")
})
public class ProjectAbsoluteTimeAutoAdvancer extends AbsoluteTimeAutoAdvancer implements AbsoluteTimeoutHandler {

	@Override
	public long getTimeoutDate(WorkItem workItem) {
		WorkflowData data = workItem.getWorkflow().getWorkflowData();
		Calendar cal = Calendar.getInstance();
		
		if (data.getMetaDataMap().containsKey("liveDate")) {			
			try {
				
				String liveDate = ((Value)data.getMetaDataMap().get("liveDate")).getString();
				
				if (liveDate.length() > 0) {
					String cleanedLiveDate = liveDate.substring(0, liveDate.lastIndexOf(":")) + "00";				
					SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
					cal.setTime(sdf.parse(cleanedLiveDate));	
				}
				
				return cal.getTimeInMillis();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		} else {
			return cal.getTimeInMillis();
		}
	}
}

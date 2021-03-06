package org.apache.hadoop.mapred;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.server.jobtracker.TaskTracker;
import org.apache.hadoop.util.ReflectionUtils;

public class JoinableDataTaskScheduler extends TaskScheduler {
	  private static final int MIN_CLUSTER_SIZE_FOR_PADDING = 3;
	  public static final Log LOG = LogFactory.getLog(JoinableDataTaskScheduler.class);
	  
	  protected JobQueueJobInProgressListener jobQueueJobInProgressListener;
	  private EagerTaskInitializationListener eagerTaskInitializationListener;
	  private float padFraction;
	  
	  //<iteration id: tasktracker id <-> map task id>
	  public Map<String, Map<String, LinkedList<Integer>>> tracker_mtask_map = new HashMap<String, Map<String, LinkedList<Integer>>>();
	  
	  //<iteration id: tasktracker id <-> reduce task id>
	  public Map<String, Map<String, LinkedList<Integer>>> tracker_rtask_map = new HashMap<String, Map<String, LinkedList<Integer>>>();
	  
	  //<iteration id: firstjob>
	  public Map<String, JobID> first_job_map = new HashMap<String, JobID>();
	  
	  //<job id: tasktracker id <-> map task id>
	  public Map<JobID, Map<String, LinkedList<Integer>>> mtask_assign_map = new HashMap<JobID, Map<String, LinkedList<Integer>>>();
	  
	//<job id: tasktracker id <-> reduce task id>, only works when users set job.setMaintainState
	  public Map<JobID, Map<String, LinkedList<Integer>>> rtask_assign_map = new HashMap<JobID, Map<String, LinkedList<Integer>>>();
	  
	  //first iteration job reduce list, only for one2one mapping
	  public Map<String, Map<String, LinkedList<Integer>>> first_job_reduces_map = new HashMap<String, Map<String, LinkedList<Integer>>>();
	
	  //one-to-mul mapping list
	  class Assignment{
		  ArrayList<Integer> maps;
		  ArrayList<Integer> reduces;
	  }
	  
	  public Map<String, Map<String, Assignment>> one2mulAssignMap = new HashMap<String, Map<String, Assignment>>();
	  
	  //<iteration id: job list>
	  public Map<String, HashSet<JobID>> iteration_jobs_map = new HashMap<String, HashSet<JobID>>();
	  
	  public JoinableDataTaskScheduler() {
	    this.jobQueueJobInProgressListener = new JobQueueJobInProgressListener();
	  }
	  
	  @Override
	  public synchronized void start() throws IOException {
		    super.start();
		    taskTrackerManager.addJobInProgressListener(jobQueueJobInProgressListener);
		    eagerTaskInitializationListener.setTaskTrackerManager(taskTrackerManager);
		    eagerTaskInitializationListener.start();
		    taskTrackerManager.addJobInProgressListener(
		        eagerTaskInitializationListener);
	  }
	  
	  @Override
	  public synchronized void terminate() throws IOException {
		    if (jobQueueJobInProgressListener != null) {
		        taskTrackerManager.removeJobInProgressListener(
		            jobQueueJobInProgressListener);
		      }
		      if (eagerTaskInitializationListener != null) {
		        taskTrackerManager.removeJobInProgressListener(
		            eagerTaskInitializationListener);
		        eagerTaskInitializationListener.terminate();
		      }
		      super.terminate();
	  }
	  
	  @Override
	  public synchronized void setConf(Configuration conf) {
	    super.setConf(conf);
	    padFraction = conf.getFloat("mapred.jobtracker.taskalloc.capacitypad", 
	                                 0.01f);
	    this.eagerTaskInitializationListener =
	    	      new EagerTaskInitializationListener(conf);
	  }

	  @Override
	  public synchronized List<Task> assignTasks(TaskTracker taskTracker)
	      throws IOException {
	    TaskTrackerStatus taskTrackerStatus = taskTracker.getStatus(); 
	    ClusterStatus clusterStatus = taskTrackerManager.getClusterStatus();
	    final int numTaskTrackers = clusterStatus.getTaskTrackers();
	    final int clusterMapCapacity = clusterStatus.getMaxMapTasks();
	    final int clusterReduceCapacity = clusterStatus.getMaxReduceTasks();

	    Collection<JobInProgress> jobQueue =
	      jobQueueJobInProgressListener.getJobQueue();

	    //
	    // Get map + reduce counts for the current tracker.
	    //
	    final int trackerMapCapacity = taskTrackerStatus.getMaxMapSlots();
	    final int trackerReduceCapacity = taskTrackerStatus.getMaxReduceSlots();
	    final int trackerRunningMaps = taskTrackerStatus.countMapTasks();
	    final int trackerRunningReduces = taskTrackerStatus.countReduceTasks();

	    // Assigned tasks
	    List<Task> assignedTasks = new ArrayList<Task>();

	    //
	    // Compute (running + pending) map and reduce task numbers across pool
	    //
	    int remainingReduceLoad = 0;
	    int remainingMapLoad = 0;
	    synchronized (jobQueue) {
	      for (JobInProgress job : jobQueue) {
	        if (job.getStatus().getRunState() == JobStatus.RUNNING) {
	          remainingMapLoad += (job.desiredMaps() - job.finishedMaps());
	          if (job.scheduleReduces()) {	
	            remainingReduceLoad += 
	              (job.desiredReduces() - job.finishedReduces());
	          }
	        }
	      }
	    }

	    // Compute the 'load factor' for maps and reduces
	    double mapLoadFactor = 0.0;
	    if (clusterMapCapacity > 0) {
	      mapLoadFactor = (double)remainingMapLoad / clusterMapCapacity;
	    }
	    double reduceLoadFactor = 0.0;
	    if (clusterReduceCapacity > 0) {
	      reduceLoadFactor = (double)remainingReduceLoad / clusterReduceCapacity;
	    }
	        
	    //
	    // In the below steps, we allocate first map tasks (if appropriate),
	    // and then reduce tasks if appropriate.  We go through all jobs
	    // in order of job arrival; jobs only get serviced if their 
	    // predecessors are serviced, too.
	    //

	    //
	    // We assign tasks to the current taskTracker if the given machine 
	    // has a workload that's less than the maximum load of that kind of
	    // task.
	    // However, if the cluster is close to getting loaded i.e. we don't
	    // have enough _padding_ for speculative executions etc., we only 
	    // schedule the "highest priority" task i.e. the task from the job 
	    // with the highest priority.
	    //
	    
	    final int trackerCurrentMapCapacity = 
	      Math.min((int)Math.ceil(mapLoadFactor * trackerMapCapacity), 
	                              trackerMapCapacity);
	    int availableMapSlots = trackerCurrentMapCapacity - trackerRunningMaps;
	    boolean exceededMapPadding = false;
	    if (availableMapSlots > 0) {
	      exceededMapPadding = 
	        exceededPadding(true, clusterStatus, trackerMapCapacity);
	    }
	    
	    int numLocalMaps = 0;
	    int numNonLocalMaps = 0;
	    scheduleMaps:
	    for (int i=0; i < availableMapSlots; ++i) {
	      synchronized (jobQueue) {
	        for (JobInProgress job : jobQueue) {
	          if (job.getStatus().getRunState() != JobStatus.RUNNING) {
	            continue;
	          }

	          if(job.getJobConf().isIterative()){
	        	  int res = assignMapForIterative(taskTracker, job, numTaskTrackers, taskTrackerStatus, 
	        			  exceededMapPadding, assignedTasks);
	        	  if(res == -1){
	        		  break;
	        	  }else if(res == 1){
	        		  break scheduleMaps;
	        	  }
	          }else if(job.getJobConf().isIncrementalStart() || job.getJobConf().isIncrementalIterative()){
	        	  int res = assignMapForIncremental(taskTracker, job, numTaskTrackers, taskTrackerStatus, 
	        			  exceededMapPadding, assignedTasks);
	        	  if(res == -1){
	        		  break;
	        	  }else if(res == 1){
	        		  break scheduleMaps;
	        	  }
	          }else{
	        	//not an iterative or incremental algorithm, normal schedule
	        	  Task t = null;
	              
	              // Try to schedule a node-local or rack-local Map task
	              t = 
	                job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
	                    numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts());
	              if (t != null) {
	                assignedTasks.add(t);
	                ++numLocalMaps;
	                
	                // Don't assign map tasks to the hilt!
	                // Leave some free slots in the cluster for future task-failures,
	                // speculative tasks etc. beyond the highest priority job
	                if (exceededMapPadding) {
	                  break scheduleMaps;
	                }
	               
	                // Try all jobs again for the next Map task 
	                break;
	              }
	              
	              // Try to schedule a node-local or rack-local Map task
	              t = 
	                job.obtainNewNonLocalMapTask(taskTrackerStatus, numTaskTrackers,
	                                       taskTrackerManager.getNumberOfUniqueHosts());
	              
	              if (t != null) {
	                assignedTasks.add(t);
	                ++numNonLocalMaps;
	                
	                // We assign at most 1 off-switch or speculative task
	                // This is to prevent TaskTrackers from stealing local-tasks
	                // from other TaskTrackers.
	                break scheduleMaps;
	              }
	          }
	        }
	      }
	    }
	    int assignedMaps = assignedTasks.size();

	    //
	    // Same thing, but for reduce tasks
	    // However we _never_ assign more than 1 reduce task per heartbeat
	    //
	    /**
	     * should maintain the reduce task location for the termination check
	     */
	    final int trackerCurrentReduceCapacity = 
	      Math.min((int)Math.ceil(reduceLoadFactor * trackerReduceCapacity), 
	               trackerReduceCapacity);
	    final int availableReduceSlots = 
	      Math.min((trackerCurrentReduceCapacity - trackerRunningReduces), 1);
	    boolean exceededReducePadding = false;
	    
	    LOG.info("availableReduceSlots " + availableReduceSlots + 
	    		"\ttrackerCurrentReduceCapacity=" + trackerCurrentReduceCapacity +
	    		"\ttrackerRunningReduces=" + trackerRunningReduces +
	    		"\treduceLoadFactor=" + reduceLoadFactor + 
	    		"\ttrackerReduceCapacity=" + trackerReduceCapacity);
	    if (availableReduceSlots > 0) {
	    	
	      exceededReducePadding = exceededPadding(false, clusterStatus, 
	                                              trackerReduceCapacity);
	      synchronized (jobQueue) {
	        for (JobInProgress job : jobQueue) {
	        	LOG.info("job " + job.getJobID() + " assigning reduce tasks!");
	          if (job.getStatus().getRunState() != JobStatus.RUNNING ||
	              job.numReduceTasks == 0) {
	        	  LOG.info("have to continue " + job.getStatus().getRunState());
	            continue;
	          }

	          Task t = null;

	          if(job.getJobConf().isIterative()){
	        	  int res = assignReduceForIterative(taskTracker, job, numTaskTrackers, taskTrackerStatus, 
	        			  exceededMapPadding, assignedTasks);
	        	  if(res == -1) break;
	          }else if(job.getJobConf().isIncrementalIterative() || job.getJobConf().isIncrementalStart()){
	        	  int res = assignReduceForIncremental(taskTracker, job, numTaskTrackers, taskTrackerStatus, 
	        			  exceededMapPadding, assignedTasks);
	        	  if(res == -1) break;
	          }else{
	        	  t = job.obtainNewReduceTask(taskTrackerStatus, numTaskTrackers, 
                          taskTrackerManager.getNumberOfUniqueHosts());
	        	  
		          if (t != null) {
			            assignedTasks.add(t);
			            break;
			      }
	          }
	        }
	      }
	    }
	    
	    if (LOG.isDebugEnabled()) {
	      LOG.debug("Task assignments for " + taskTrackerStatus.getTrackerName() + " --> " +
	                "[" + mapLoadFactor + ", " + trackerMapCapacity + ", " + 
	                trackerCurrentMapCapacity + ", " + trackerRunningMaps + "] -> [" + 
	                (trackerCurrentMapCapacity - trackerRunningMaps) + ", " +
	                assignedMaps + " (" + numLocalMaps + ", " + numNonLocalMaps + 
	                ")] [" + reduceLoadFactor + ", " + trackerReduceCapacity + ", " + 
	                trackerCurrentReduceCapacity + "," + trackerRunningReduces + 
	                "] -> [" + (trackerCurrentReduceCapacity - trackerRunningReduces) + 
	                ", " + (assignedTasks.size()-assignedMaps) + "]");
	    }

	    return assignedTasks;
	  }

	  private int assignMapForIterative(TaskTracker taskTracker, JobInProgress job,
			  int numTaskTrackers, TaskTrackerStatus taskTrackerStatus, boolean exceededMapPadding, 
			  List<Task> assignedTasks) throws IOException{
		  boolean newIterationJob = false;
		  Task t = null;
		  
    	  String iterativeAppID = job.getJobConf().getIterativeAlgorithmID();
          
    	  if(iterativeAppID.equals("none")){
    		  throw new IOException("please specify the iteration ID!");
    	  }

    	  String jointype = job.getJobConf().get("mapred.iterative.jointype");
    	  
    	//prepare the iterationid map and jobtask map
          if(!this.tracker_mtask_map.containsKey(iterativeAppID)){
        	  //a new iterative algorithm
        	  Map<String, LinkedList<Integer>> new_tracker_task_map = new HashMap<String, LinkedList<Integer>>();
        	  this.tracker_mtask_map.put(iterativeAppID, new_tracker_task_map);
        	  
        	  Map<String, LinkedList<Integer>> new_tracker_rtask_map = new HashMap<String, LinkedList<Integer>>();
        	  this.tracker_rtask_map.put(iterativeAppID, new_tracker_rtask_map);
        	  
        	  //record the first job of the series of jobs in the iterations
        	  this.first_job_map.put(iterativeAppID, job.getJobID());
        	  
        	  //record the list of jobs for a iteration
        	  HashSet<JobID> jobs = new HashSet<JobID>();
        	  jobs.add(job.getJobID());
        	  this.iteration_jobs_map.put(iterativeAppID, jobs);
          }
          
          //this is the first job of the series of jobs
          if(this.first_job_map.get(iterativeAppID).equals(job.getJobID()) 
        		  && job.getJobConf().isIterative()){
        	  LOG.info(job.getJobID() + " is the first iteration job");
        	  newIterationJob = true;
          }
          
    	  
          //this is one of the following jobs, and prepare a assignment list for the assignment
          if(!newIterationJob){
        	  LOG.info(job.getJobID() + " is not the first iteration job");
        	  this.iteration_jobs_map.get(iterativeAppID).add(job.getJobID());
        	  
        	  if(this.mtask_assign_map.get(job.getJobID()) == null){
	        	  //prepare the map task assignment list
        		  LOG.info("for job " + job.getJobID() + "'s assignment:");
	        	  Map<String, LinkedList<Integer>> map_task_assign = new HashMap<String, LinkedList<Integer>>();
	        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_mtask_map.get(iterativeAppID).entrySet()){
	        		  String tracker = entry.getKey();
	        		  LinkedList<Integer> taskids = entry.getValue();
	        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
	        		  LOG.info("assign on tracker " + tracker);
	        		  for(int taskid : taskids){
	        			  copytaskids.add(taskid);
	        			  LOG.info("task id " + taskid);
	        		  }
	        		  map_task_assign.put(tracker, copytaskids);
	        	  }
	        	  this.mtask_assign_map.put(job.getJobID(), map_task_assign);
	        	  
	        	  //if one2one copy the map assign to reduce assign, the are with the same mapping
	        	  if(jointype.equals("one2one")){
		        	  //prepare the reduce task assignment list
		        	  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_mtask_map.get(iterativeAppID).entrySet()){
		        		  String tracker = entry.getKey();
		        		  LinkedList<Integer> taskids = entry.getValue();
		        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
		        		  for(int taskid : taskids){
		        			  copytaskids.add(taskid);
		        		  }
		        		  reduce_task_assign.put(tracker, copytaskids);
		        	  }
		        	  this.tracker_rtask_map.put(iterativeAppID, reduce_task_assign);
	        	  }
	        	  
	        	  
	        	  //prepare the reduce task assignment list for all cases
	        	  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
	        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_rtask_map.get(iterativeAppID).entrySet()){
	        		  String tracker = entry.getKey();
	        		  LinkedList<Integer> taskids = entry.getValue();
	        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
	        		  for(int taskid : taskids){
	        			  copytaskids.add(taskid);
	        		  }
	        		  reduce_task_assign.put(tracker, copytaskids);
	        	  }
	        	  this.rtask_assign_map.put(job.getJobID(), reduce_task_assign);
	        	  
        	  }

          }
          
          
          
          //the first iteration or following iteration
          //if the first iteration: assign taskid by default (exception for the one2mul case, where we assign staring from 0,...,n)
          //else if the following iterations: assign taskid based on the first iteration assignment
          if(newIterationJob){	
        	  
        	  /**
        	   * the one2mul case should be carefully taken care, we want to assgin map0,map1,map2 and reduce0 to a tracker,
        	   * and assign map3,map4,map5 and reduce1 to another tracker
        	   */
        	  LOG.info("jointype is " + jointype);
        	  
        	  if(jointype.equals("one2mul") && !tracker_rtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
        		  
        		  //if contain the tracker, that means we have assigned tasks for this tracker
   
        		  int scala = job.getJobConf().getInt("mapred.iterative.data.scala", 1);
    			  //int mapsEachTracker = job.getJobConf().getNumMapTasks() / numTaskTrackers;
    			  int reducersEachTracker = job.getJobConf().getNumReduceTasks() / numTaskTrackers;
    			  if(job.getJobConf().getNumReduceTasks() % numTaskTrackers != 0)
    				  throw new IOException("job.getJobConf().getNumReduceTasks() % numTaskTrackers != 0");
    			  
			          if(!this.tracker_mtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
			        	  LinkedList<Integer> tasklist = new LinkedList<Integer>();
			        	  this.tracker_mtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
		          }
			          if(!this.tracker_rtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
			        	  LinkedList<Integer> tasklist = new LinkedList<Integer>();
			        	  this.tracker_rtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
		          }
			          
			          //for debugging
    			  String debugout1 = "maps: ";
    			  String debugout2 = "reduces: ";
    			  
				  int reduceOffsetId = (tracker_rtask_map.get(iterativeAppID).size()-1) * reducersEachTracker; //the start reduce id
				  
				  for(int count = 0; count < reducersEachTracker; count++){
					  int reducepartitionid = reduceOffsetId + count;
					  debugout2 += reducepartitionid + " ";
					  tracker_rtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(reducepartitionid);
					  
					  for(int count2=0; count2<scala; count2++){
						  int mappartitionid = reducepartitionid*scala + count2;
						  //int mapid = job.splitTaskMap.get(mappartitionid);
						  debugout1 += mappartitionid + " ";
						  this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(mappartitionid);
					  }
				  }
				  
				  //print out for debug
				  LOG.info("tracker " + taskTracker.getTrackerName() + " assigned tasks " + debugout1 + " and " + debugout2);
        		  
				  //make the assignment list
        		  String tracker = taskTracker.getTrackerName();
        		  LinkedList<Integer> mtaskids = this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName());
        		  LinkedList<Integer> mcopytaskids = new LinkedList<Integer>();
        		  for(int taskid : mtaskids){
        			  mcopytaskids.add(taskid);
        		  }
        		  if(!mtask_assign_map.containsKey(job.getJobID())){
	        		  Map<String, LinkedList<Integer>> map_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  this.mtask_assign_map.put(job.getJobID(), map_task_assign);
        		  }
        		  this.mtask_assign_map.get(job.getJobID()).put(tracker, mcopytaskids);
        		  
	        	  //prepare the reduce task assignment list
        		  LinkedList<Integer> rtaskids = this.tracker_rtask_map.get(iterativeAppID).get(taskTracker.getTrackerName());
        		  LinkedList<Integer> rcopytaskids = new LinkedList<Integer>();
        		  for(int taskid : rtaskids){
        			  rcopytaskids.add(taskid);
        		  }
        		  if(!rtask_assign_map.containsKey(job.getJobID())){
	        		  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  this.rtask_assign_map.put(job.getJobID(), reduce_task_assign);
        		  }
	        	  this.rtask_assign_map.get(job.getJobID()).put(tracker, rcopytaskids);
	        	  
				  //assign a map task for this tracker
	        	  Integer target = null;
	        	  try{
	        		  target = this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
	        	  }catch (Exception e){
	        		  e.printStackTrace();
	        	  }
	        	  
	        	  if(target == null){
	        		  //all have been assigned, no more work, maybe it should help others to process
	        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
	        		  return -1;
	        	  }else{
			          t = job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
				                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts(), target);
	        	  }
	        	  
        	  }else{
        		  t = job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
			                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts()); 
        	  }
        	  
          }else{
        	  Integer target = null;
        	  try{
        		  target = this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
        	  }catch (Exception e){
        		  e.printStackTrace();
        	  }
        	  
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
		          t = job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
			                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
          }
          

          if (t != null) {
            assignedTasks.add(t);
            
            //new iteration job and the first task for a tasktracker
            //for one2mul case, we don't need to record the assignment, since we already made the assignment list beforehand
            if(!newIterationJob || jointype.equals("one2mul")){
            	//poll, remove
            	this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
            	LOG.info("assigning task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }else{
            	//record the assignment list for map tasks
	            if(!this.tracker_mtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
	            	LinkedList<Integer> tasklist = new LinkedList<Integer>();
	            	this.tracker_mtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
	            }
	            
	            this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
            

	            //prepare the reduce assignment, for mapping with reduce
	            if(jointype.equals("one2one")){
		            //prepare the reduce assignment, for mapping with reduce
        		    if(!first_job_reduces_map.containsKey(iterativeAppID)){
        		    	Map<String, LinkedList<Integer>> tracker_reduce_map = new HashMap<String, LinkedList<Integer>>();
        		    	first_job_reduces_map.put(iterativeAppID, tracker_reduce_map);
        		    }
        		  
        		    if(!first_job_reduces_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
        			    LinkedList<Integer> reduces = new LinkedList<Integer>();
        			    first_job_reduces_map.get(iterativeAppID).put(taskTracker.getTrackerName(), reduces);
        		    }
        		   
        		    first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
	            }

	            LOG.info("assigning task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }


            // Don't assign map tasks to the hilt!
            // Leave some free slots in the cluster for future task-failures,
            // speculative tasks etc. beyond the highest priority job
            if (exceededMapPadding) {
              return 1;
            }
           
            // Try all jobs again for the next Map task 
            return -1;
          }
          
          LOG.error("New Node Or Rack Local Map Task failed!");
          
          
          if(newIterationJob){
        	// Try to schedule a node-local or rack-local Map task
	          t = job.obtainNewNonLocalMapTask(taskTrackerStatus, numTaskTrackers,
	  	                                   taskTrackerManager.getNumberOfUniqueHosts());
          }else{
        	  Integer target = this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
        	  
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
		          t = job.obtainNewNonLocalMapTask(taskTrackerStatus, numTaskTrackers, 
		        		  taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
          }

          
          if (t != null) {
            assignedTasks.add(t);
            
            //new iteration job and the first task for a tasktracker
            //for one2mul case, we don't need to record the assignment, since we already made the assignment list beforehand
            if(!newIterationJob || jointype.equals("one2mul")){
            	//poll, remove
            	this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
            	LOG.info("assigning task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }else{
            	//record the assignment list for map tasks
	            if(!this.tracker_mtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
	            	LinkedList<Integer> tasklist = new LinkedList<Integer>();
	            	this.tracker_mtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
	            }
	            
	            this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
            

	            //prepare the reduce assignment, for mapping with reduce
	            if(jointype.equals("one2one")){
		            //prepare the reduce assignment, for mapping with reduce
        		    if(!first_job_reduces_map.containsKey(iterativeAppID)){
        		    	Map<String, LinkedList<Integer>> tracker_reduce_map = new HashMap<String, LinkedList<Integer>>();
        		    	first_job_reduces_map.put(iterativeAppID, tracker_reduce_map);
        		    }
        		  
        		    if(!first_job_reduces_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
        			    LinkedList<Integer> reduces = new LinkedList<Integer>();
        			    first_job_reduces_map.get(iterativeAppID).put(taskTracker.getTrackerName(), reduces);
        		    }
        		   
        		    first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
	            }

	            LOG.info("assigning task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }


            // Don't assign map tasks to the hilt!
            // Leave some free slots in the cluster for future task-failures,
            // speculative tasks etc. beyond the highest priority job
            if (exceededMapPadding) {
              return 1;
            }
           
            // Try all jobs again for the next Map task 
            return -1;
          }
          
          return 0;
	  }
	  
	  private int assignReduceForIterative(TaskTracker taskTracker, JobInProgress job,
			  int numTaskTrackers, TaskTrackerStatus taskTrackerStatus, boolean exceededMapPadding, 
			  List<Task> assignedTasks) throws IOException{
		  boolean newIterationJob = false;
		  Task t = null;
		  
    	  String iterativeAppID = job.getJobConf().getIterativeAlgorithmID();
    	  if(iterativeAppID.equals("none")){
    		  throw new IOException("please specify the iteration ID!");
    	  }
    	  
    	  String jointype = job.getJobConf().get("mapred.iterative.jointype");
    	  
    	  if(jointype.equals("one2one")){
    		  //one-to-one or one-to-mul jobs
	          if(this.first_job_map.get(iterativeAppID).equals(job.getJobID()) 
	        		  && job.getJobConf().isIterative()){
	        	  LOG.info(job.getJobID() + " is the first iteration job for reduce");
	        	  newIterationJob = true;
	          }
	          
        	  Integer target = null;
        	  if(newIterationJob){
        		  if(first_job_reduces_map.get(iterativeAppID) == null){
        			  throw new IOException("I think something is wrong since the tasktracker never receive " +
        			  		"a map task with iterativeapp id " + iterativeAppID);
        		  } 
        		  
        		  if(first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()) == null){
        			  throw new IOException("I think something is wrong since the tasktracker never receive " +
        			  		"a map task with iterativeapp id " + iterativeAppID + " from " + taskTracker.getTrackerName());
        		  }
        		  
        		  LOG.info("jobtracker task buffers size " + first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).size());
        		  target = this.first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).pollFirst();
        	  }else{
        		//the task assignment has already been processed during the map task assignment, so never use tracker_rtask_map
        		  target = this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
        	  }
        	  
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all reduce tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
    	          t = job.obtainNewReduceTask(taskTrackerStatus, numTaskTrackers, 
    	  	                                    taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
         }else if(jointype.equals("one2mul")){
        	 Integer target = this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
        	 
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all reduce tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
    	          t = job.obtainNewReduceTask(taskTrackerStatus, numTaskTrackers, 
    	  	                                    taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
         }else{
    		 //one-to-all case, assign tasks in the first iteration job, and remember this mapping
	          
	          //this is the first job of the series of jobs
	          if(this.first_job_map.get(iterativeAppID).equals(job.getJobID())){
	        	  LOG.info(job.getJobID() + " is the first iteration job for reduce");
	        	  newIterationJob = true;
	          }
	          /*
	          //this is one of the following jobs, and prepare a assignment list for the assignment
	          else{
	        	  LOG.info(job.getJobID() + " is not the first iteration job for reduce");
	        	  if(this.rtask_assign_map.get(job.getJobID()) == null){
		        	  //prepare the map task assignment list
		        	  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_rtask_map.get(iterativeAppID).entrySet()){
		        		  String tracker = entry.getKey();
		        		  LinkedList<Integer> taskids = entry.getValue();
		        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
		        		  for(int taskid : taskids){
		        			  copytaskids.add(taskid);
		        		  }
		        		  reduce_task_assign.put(tracker, copytaskids);
		        	  }
		        	  this.rtask_assign_map.put(job.getJobID(), reduce_task_assign);
	        	  }

	          }
	          */
	          
	          //the first iteration or following iteration
	          //if the first iteration: assign taskid by default
	          //else if the following iterations: assign taskid based on the first iteration assignment
	          if(newIterationJob){
		          t = job.obtainNewReduceTask(taskTrackerStatus, 
		                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts());
		          
		          if(t != null){
			            if(!this.tracker_rtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
			            	LinkedList<Integer> tasklist = new LinkedList<Integer>();
			            	this.tracker_rtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
			            }
			            
			            this.tracker_rtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
			        	  LOG.info("assigning reduce task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
		          }
	          }else{
	        	  Integer target = this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
	        	  
	        	  if(target == null){
	        		  //all have been assigned, no more work, maybe it should help others to process
	        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
	        		  return -1;
	        	  }else{
			          t = job.obtainNewReduceTask(taskTrackerStatus, 
				                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts(), target);
	        	  }
	        	  
	        	  if(t != null){
		            	//poll, remove
		            	this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
		            	LOG.info("assigning reduce task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
	        	  }
	          }
    	  }
    	  
    	  if(t != null){
    		  assignedTasks.add(t);
    		  return -1;
    	  }
    	  
    	  return 0;
	  }
	  
	  private int assignMapForIncremental(TaskTracker taskTracker, JobInProgress job,
			  int numTaskTrackers, TaskTrackerStatus taskTrackerStatus, boolean exceededMapPadding, 
			  List<Task> assignedTasks) throws IOException{

		  Task t = null;
		  
    	  String iterativeAppID = job.getJobConf().getIterativeAlgorithmID();
          
    	  if(iterativeAppID.equals("none")){
    		  throw new IOException("please specify the iteration ID!");
    	  }

    	  String jointype = job.getJobConf().get("mapred.iterative.jointype");
    	  
    	//prepare the iterationid map and jobtask map
          if(!this.tracker_mtask_map.containsKey(iterativeAppID) && job.getJobConf().isIncrementalStart()){
        	  //a new iterative algorithm
        	  Map<String, LinkedList<Integer>> new_tracker_task_map = new HashMap<String, LinkedList<Integer>>();
        	  this.tracker_mtask_map.put(iterativeAppID, new_tracker_task_map);
        	  
        	  Map<String, LinkedList<Integer>> new_tracker_rtask_map = new HashMap<String, LinkedList<Integer>>();
        	  this.tracker_rtask_map.put(iterativeAppID, new_tracker_rtask_map);
        	  
        	  //record the first job of the series of jobs in the iterations
        	  this.first_job_map.put(iterativeAppID, job.getJobID());
        	  
        	  //record the list of jobs for a iteration
        	  HashSet<JobID> jobs = new HashSet<JobID>();
        	  jobs.add(job.getJobID());
        	  this.iteration_jobs_map.put(iterativeAppID, jobs);
          }
          
          //this is the first job of the series of jobs
          if(this.first_job_map.get(iterativeAppID).equals(job.getJobID()) 
        		  && job.getJobConf().isIterative()){
        	  LOG.info(job.getJobID() + " is the first iteration job");
          }
          
          //this is one of the following jobs, and prepare a assignment list for the assignment
          if(job.getJobConf().isIncrementalIterative()){
        	  LOG.info(job.getJobID() + " is not the first iteration job");
        	  this.iteration_jobs_map.get(iterativeAppID).add(job.getJobID());
        	  
        	  if(this.mtask_assign_map.get(job.getJobID()) == null){
	        	  //prepare the map task assignment list
        		  LOG.info("for job " + job.getJobID() + "'s assignment:");
	        	  Map<String, LinkedList<Integer>> map_task_assign = new HashMap<String, LinkedList<Integer>>();
	        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_mtask_map.get(iterativeAppID).entrySet()){
	        		  String tracker = entry.getKey();
	        		  LinkedList<Integer> taskids = entry.getValue();
	        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
	        		  LOG.info("assign on tracker " + tracker);
	        		  for(int taskid : taskids){
	        			  copytaskids.add(taskid);
	        			  LOG.info("task id " + taskid);
	        		  }
	        		  map_task_assign.put(tracker, copytaskids);
	        	  }
	        	  this.mtask_assign_map.put(job.getJobID(), map_task_assign);
	        	  
	        	  //if one2one copy the map assign to reduce assign, the are with the same mapping
	        	  if(jointype.equals("one2one")){
		        	  //prepare the reduce task assignment list
		        	  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_mtask_map.get(iterativeAppID).entrySet()){
		        		  String tracker = entry.getKey();
		        		  LinkedList<Integer> taskids = entry.getValue();
		        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
		        		  for(int taskid : taskids){
		        			  copytaskids.add(taskid);
		        		  }
		        		  reduce_task_assign.put(tracker, copytaskids);
		        	  }
		        	  this.tracker_rtask_map.put(iterativeAppID, reduce_task_assign);
	        	  }
	        	  
	        	  
	        	  //prepare the reduce task assignment list for all cases
	        	  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
	        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_rtask_map.get(iterativeAppID).entrySet()){
	        		  String tracker = entry.getKey();
	        		  LinkedList<Integer> taskids = entry.getValue();
	        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
	        		  for(int taskid : taskids){
	        			  copytaskids.add(taskid);
	        		  }
	        		  reduce_task_assign.put(tracker, copytaskids);
	        	  }
	        	  this.rtask_assign_map.put(job.getJobID(), reduce_task_assign);
	        	  
        	  }

          }
          
          
          
          //the first iteration or following iteration
          //if the first iteration: assign taskid by default (exception for the one2mul case, where we assign staring from 0,...,n)
          //else if the following iterations: assign taskid based on the first iteration assignment
          if(job.getJobConf().isIncrementalStart()){	
        	  
        	  /**
        	   * the one2mul case should be carefully taken care, we want to assgin map0,map1,map2 and reduce0 to a tracker,
        	   * and assign map3,map4,map5 and reduce1 to another tracker
        	   */
        	  LOG.info("jointype is " + jointype);
        	  
        	  if(jointype.equals("one2mul") && !tracker_rtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
        		  
        		  //if contain the tracker, that means we have assigned tasks for this tracker
   
        		  int scala = job.getJobConf().getInt("mapred.iterative.data.scala", 1);
    			  //int mapsEachTracker = job.getJobConf().getNumMapTasks() / numTaskTrackers;
    			  int reducersEachTracker = job.getJobConf().getNumReduceTasks() / numTaskTrackers;
    			  if(job.getJobConf().getNumReduceTasks() % numTaskTrackers != 0)
    				  throw new IOException("job.getJobConf().getNumReduceTasks() % numTaskTrackers != 0");
    			  
			          if(!this.tracker_mtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
			        	  LinkedList<Integer> tasklist = new LinkedList<Integer>();
			        	  this.tracker_mtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
		          }
			          if(!this.tracker_rtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
			        	  LinkedList<Integer> tasklist = new LinkedList<Integer>();
			        	  this.tracker_rtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
		          }
			          
			          //for debugging
    			  String debugout1 = "maps: ";
    			  String debugout2 = "reduces: ";
    			  
				  int reduceOffsetId = (tracker_rtask_map.get(iterativeAppID).size()-1) * reducersEachTracker; //the start reduce id
				  
				  for(int count = 0; count < reducersEachTracker; count++){
					  int reducepartitionid = reduceOffsetId + count;
					  debugout2 += reducepartitionid + " ";
					  tracker_rtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(reducepartitionid);
					  
					  for(int count2=0; count2<scala; count2++){
						  int mappartitionid = reducepartitionid*scala + count2;
						  //int mapid = job.splitTaskMap.get(mappartitionid);
						  debugout1 += mappartitionid + " ";
						  this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(mappartitionid);
					  }
				  }
				  
				  //print out for debug
				  LOG.info("tracker " + taskTracker.getTrackerName() + " assigned tasks " + debugout1 + " and " + debugout2);
        		  
				  //make the assignment list
        		  String tracker = taskTracker.getTrackerName();
        		  LinkedList<Integer> mtaskids = this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName());
        		  LinkedList<Integer> mcopytaskids = new LinkedList<Integer>();
        		  for(int taskid : mtaskids){
        			  mcopytaskids.add(taskid);
        		  }
        		  if(!mtask_assign_map.containsKey(job.getJobID())){
	        		  Map<String, LinkedList<Integer>> map_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  this.mtask_assign_map.put(job.getJobID(), map_task_assign);
        		  }
        		  this.mtask_assign_map.get(job.getJobID()).put(tracker, mcopytaskids);
        		  
	        	  //prepare the reduce task assignment list
        		  LinkedList<Integer> rtaskids = this.tracker_rtask_map.get(iterativeAppID).get(taskTracker.getTrackerName());
        		  LinkedList<Integer> rcopytaskids = new LinkedList<Integer>();
        		  for(int taskid : rtaskids){
        			  rcopytaskids.add(taskid);
        		  }
        		  if(!rtask_assign_map.containsKey(job.getJobID())){
	        		  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  this.rtask_assign_map.put(job.getJobID(), reduce_task_assign);
        		  }
	        	  this.rtask_assign_map.get(job.getJobID()).put(tracker, rcopytaskids);
	        	  
				  //assign a map task for this tracker
	        	  Integer target = null;
	        	  try{
	        		  target = this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
	        	  }catch (Exception e){
	        		  e.printStackTrace();
	        	  }
	        	  
	        	  if(target == null){
	        		  //all have been assigned, no more work, maybe it should help others to process
	        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
	        		  return -1;
	        	  }else{
			          t = job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
				                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts(), target);
	        	  }
	        	  
        	  }else{
        		  t = job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
			                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts()); 
        	  }
        	  
          }else{
        	  Integer target = null;
        	  try{
        		  target = this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
        	  }catch (Exception e){
        		  e.printStackTrace();
        	  }
        	  
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
		          t = job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
			                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
          }
          

          if (t != null) {
            assignedTasks.add(t);
            
            //new iteration job and the first task for a tasktracker
            //for one2mul case, we don't need to record the assignment, since we already made the assignment list beforehand
            if(job.getJobConf().isIncrementalIterative() || jointype.equals("one2mul")){
            	//poll, remove
            	this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
            	LOG.info("assigning task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }else{
            	//record the assignment list for map tasks
	            if(!this.tracker_mtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
	            	LinkedList<Integer> tasklist = new LinkedList<Integer>();
	            	this.tracker_mtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
	            }
	            
	            this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
            
	            //prepare the reduce assignment, for mapping with reduce
	            if(jointype.equals("one2one")){
		            //prepare the reduce assignment, for mapping with reduce
        		    if(!first_job_reduces_map.containsKey(iterativeAppID)){
        		    	Map<String, LinkedList<Integer>> tracker_reduce_map = new HashMap<String, LinkedList<Integer>>();
        		    	first_job_reduces_map.put(iterativeAppID, tracker_reduce_map);
        		    }
        		  
        		    if(!first_job_reduces_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
        			    LinkedList<Integer> reduces = new LinkedList<Integer>();
        			    first_job_reduces_map.get(iterativeAppID).put(taskTracker.getTrackerName(), reduces);
        		    }
        		   
        		    first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
        		    
        		    LOG.info("hahahaha " + first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).size());
	            }

	            LOG.info("assigning task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }


            // Don't assign map tasks to the hilt!
            // Leave some free slots in the cluster for future task-failures,
            // speculative tasks etc. beyond the highest priority job
            if (exceededMapPadding) {
              return 1;
            }
           
            // Try all jobs again for the next Map task 
            return -1;
          }
          
          LOG.info("New Node Or Rack Local Map Task failed! or map tasks have been completed");
          
          
          if(job.getJobConf().isIncrementalStart()){
        	// Try to schedule a node-local or rack-local Map task
	          t = job.obtainNewNonLocalMapTask(taskTrackerStatus, numTaskTrackers,
	  	                                   taskTrackerManager.getNumberOfUniqueHosts());
          }else{
        	  Integer target = this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
        	  
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
		          t = job.obtainNewNonLocalMapTask(taskTrackerStatus, numTaskTrackers, 
		        		  taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
          }

          
          if (t != null) {
            assignedTasks.add(t);
            
            //new iteration job and the first task for a tasktracker
            //for one2mul case, we don't need to record the assignment, since we already made the assignment list beforehand
            if(job.getJobConf().isIncrementalIterative() || jointype.equals("one2mul")){
            	//poll, remove
            	this.mtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
            	LOG.info("assigning non local/rack task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }else{
            	//record the assignment list for map tasks
	            if(!this.tracker_mtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
	            	LinkedList<Integer> tasklist = new LinkedList<Integer>();
	            	this.tracker_mtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
	            }
	            
	            this.tracker_mtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
            
	            //prepare the reduce assignment, for mapping with reduce
	            if(jointype.equals("one2one")){
		            //prepare the reduce assignment, for mapping with reduce
        		    if(!first_job_reduces_map.containsKey(iterativeAppID)){
        		    	Map<String, LinkedList<Integer>> tracker_reduce_map = new HashMap<String, LinkedList<Integer>>();
        		    	first_job_reduces_map.put(iterativeAppID, tracker_reduce_map);
        		    }
        		  
        		    if(!first_job_reduces_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
        			    LinkedList<Integer> reduces = new LinkedList<Integer>();
        			    first_job_reduces_map.get(iterativeAppID).put(taskTracker.getTrackerName(), reduces);
        		    }
        		   
        		    first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
        		    
        		    LOG.info("hahahaha " + first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).size());
	            }

	            LOG.info("assigning non local/rack task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
            }


            // Don't assign map tasks to the hilt!
            // Leave some free slots in the cluster for future task-failures,
            // speculative tasks etc. beyond the highest priority job
            if (exceededMapPadding) {
              return 1;
            }
           
            // Try all jobs again for the next Map task 
            return -1;
          }
          
          return 0;
	  }
	  
	  private int assignReduceForIncremental(TaskTracker taskTracker, JobInProgress job,
			  int numTaskTrackers, TaskTrackerStatus taskTrackerStatus, boolean exceededMapPadding, 
			  List<Task> assignedTasks) throws IOException{
		  
		  Task t = null;
		  
    	  String iterativeAppID = job.getJobConf().getIterativeAlgorithmID();
    	  
    	  if(iterativeAppID.equals("none")){
    		  throw new IOException("please specify the iteration ID!");
    	  }
    	  
    	  String jointype = job.getJobConf().get("mapred.iterative.jointype");
    	  
    	  if(jointype.equals("one2one")){
    		  //one-to-one or one-to-mul jobs
    		  
	          if(this.first_job_map.get(iterativeAppID).equals(job.getJobID()) 
	        		  && job.getJobConf().isIncrementalStart()){
	        	  LOG.info(job.getJobID() + " is the first iteration job for reduce");
	          }
	          
        	  Integer target = null;
        	  if(job.getJobConf().isIncrementalStart()){
        		  
        		  if(first_job_reduces_map.get(iterativeAppID) == null){
        			  throw new IOException("I think something is wrong since the tasktracker never receive " +
        			  		"a map task with iterativeapp id " + iterativeAppID);
        		  } 
        		  
        		  if(first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()) == null){
        			  throw new IOException("I think something is wrong since the tasktracker never receive " +
        			  		"a map task with iterativeapp id " + iterativeAppID + " from " + taskTracker.getTrackerName());
        		  }
        		  
        		  LOG.info("the reduce # on tracker " + taskTracker.getTrackerName() + " is " + 
        				  first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).size());
        		  
        		  target = this.first_job_reduces_map.get(iterativeAppID).get(taskTracker.getTrackerName()).pollFirst();
        	  }else{
        		//the task assignment has already been processed during the map task assignment, so never use tracker_rtask_map
        		  target = this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
        	  }
        	  
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all reduce tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
        		  LOG.info("assigning task " + target + " on " + taskTracker.getTrackerName());
    	          t = job.obtainNewReduceTask(taskTrackerStatus, numTaskTrackers, 
    	  	                                    taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
        	  
         }else if(jointype.equals("one2mul")){
        	 Integer target = this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
        	 
        	  if(target == null){
        		  //all have been assigned, no more work, maybe it should help others to process
        		  LOG.info("all reduce tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
        		  return -1;
        	  }else{
    	          t = job.obtainNewReduceTask(taskTrackerStatus, numTaskTrackers, 
    	  	                                    taskTrackerManager.getNumberOfUniqueHosts(), target);
        	  }
         }else{
    		 //one-to-all case, assign tasks in the first iteration job, and remember this mapping
	          
	          //this is the first job of the series of jobs
	          if(this.first_job_map.get(iterativeAppID).equals(job.getJobID())){
	        	  LOG.info(job.getJobID() + " is the first iteration job for reduce");
	          }
	          /*
	          //this is one of the following jobs, and prepare a assignment list for the assignment
	          else{
	        	  LOG.info(job.getJobID() + " is not the first iteration job for reduce");
	        	  if(this.rtask_assign_map.get(job.getJobID()) == null){
		        	  //prepare the map task assignment list
		        	  Map<String, LinkedList<Integer>> reduce_task_assign = new HashMap<String, LinkedList<Integer>>();
		        	  for(Map.Entry<String, LinkedList<Integer>> entry : this.tracker_rtask_map.get(iterativeAppID).entrySet()){
		        		  String tracker = entry.getKey();
		        		  LinkedList<Integer> taskids = entry.getValue();
		        		  LinkedList<Integer> copytaskids = new LinkedList<Integer>();
		        		  for(int taskid : taskids){
		        			  copytaskids.add(taskid);
		        		  }
		        		  reduce_task_assign.put(tracker, copytaskids);
		        	  }
		        	  this.rtask_assign_map.put(job.getJobID(), reduce_task_assign);
	        	  }

	          }
	          */
	          
	          //the first iteration or following iteration
	          //if the first iteration: assign taskid by default
	          //else if the following iterations: assign taskid based on the first iteration assignment
	          if(job.getJobConf().isIncrementalStart()){
		          t = job.obtainNewReduceTask(taskTrackerStatus, 
		                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts());
		          
		          if(t != null){
			            if(!this.tracker_rtask_map.get(iterativeAppID).containsKey(taskTracker.getTrackerName())){
			            	LinkedList<Integer> tasklist = new LinkedList<Integer>();
			            	this.tracker_rtask_map.get(iterativeAppID).put(taskTracker.getTrackerName(), tasklist);
			            }
			            
			            this.tracker_rtask_map.get(iterativeAppID).get(taskTracker.getTrackerName()).add(t.getTaskID().getTaskID().getId());
			        	  LOG.info("assigning reduce task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
		          }
	          }else{
	        	  Integer target = this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).peekFirst();
	        	  
	        	  if(target == null){
	        		  //all have been assigned, no more work, maybe it should help others to process
	        		  LOG.info("all map tasks on tasktracker " + taskTracker.getTrackerName() + " have been processed");
	        		  return -1;
	        	  }else{
			          t = job.obtainNewReduceTask(taskTrackerStatus, 
				                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts(), target);
	        	  }
	        	  
	        	  if(t != null){
		            	//poll, remove
		            	this.rtask_assign_map.get(job.getJobID()).get(taskTracker.getTrackerName()).pollFirst();
		            	LOG.info("assigning reduce task " + t.getTaskID() + " on " + taskTracker.getTrackerName());
	        	  }
	          }
    	  }
    	  
    	  if(t != null){
    		  assignedTasks.add(t);
    		  return -1;
    	  }
    	  
    	  return 0;
	  }
	  
	  private boolean exceededPadding(boolean isMapTask, 
	                                  ClusterStatus clusterStatus, 
	                                  int maxTaskTrackerSlots) { 
	    int numTaskTrackers = clusterStatus.getTaskTrackers();
	    int totalTasks = 
	      (isMapTask) ? clusterStatus.getMapTasks() : 
	        clusterStatus.getReduceTasks();
	    int totalTaskCapacity = 
	      isMapTask ? clusterStatus.getMaxMapTasks() : 
	                  clusterStatus.getMaxReduceTasks();

	    Collection<JobInProgress> jobQueue =
	      jobQueueJobInProgressListener.getJobQueue();

	    boolean exceededPadding = false;
	    synchronized (jobQueue) {
	      int totalNeededTasks = 0;
	      for (JobInProgress job : jobQueue) {
	        if (job.getStatus().getRunState() != JobStatus.RUNNING ||
	            job.numReduceTasks == 0) {
	          continue;
	        }

	        //
	        // Beyond the highest-priority task, reserve a little 
	        // room for failures and speculative executions; don't 
	        // schedule tasks to the hilt.
	        //
	        totalNeededTasks += 
	          isMapTask ? job.desiredMaps() : job.desiredReduces();
	        int padding = 0;
	        if (numTaskTrackers > MIN_CLUSTER_SIZE_FOR_PADDING) {
	          padding = 
	            Math.min(maxTaskTrackerSlots,
	                     (int) (totalNeededTasks * padFraction));
	        }
	        if (totalTasks + padding >= totalTaskCapacity) {
	          exceededPadding = true;
	          break;
	        }
	      }
	    }

	    return exceededPadding;
	  }

	  @Override
	  public synchronized Collection<JobInProgress> getJobs(String queueName) {
	    return jobQueueJobInProgressListener.getJobQueue();
	  }  
}

import logging
import threading

from Pegasus.shadowq.jobstate import JSLogEvent
from Pegasus.shadowq.util import Enum

__all__ = ["JobState","Job","DAG","DAGException","parse_dag"]

log = logging.getLogger(__name__)

JobState = Enum([
    "UNREADY",
    "READY",
    "PRESCRIPT",
    "QUEUED",
    "RUNNING",
    "POSTSCRIPT",
    "SUCCESSFUL",
    "FAILED"
])

class Job(object):
    def __init__(self, name):
        self.name = name
        self.runtime = 0.0
        self.state = JobState.UNREADY
        self.prescript = False
        self.postscript = False
        self.parents = []
        self.children = []

    def process_jslog_record(self, record):
        if self.state == JobState.SUCCESSFUL:
            raise DAGException("Invalid state: Successful job %s got event %s" % (self.name, record.event))

        if record.event == JSLogEvent.PRE_SCRIPT_STARTED:
            self.state = JobState.PRESCRIPT
        elif record.event ==JSLogEvent.PRE_SCRIPT_TERMINATED:
            pass
        elif record.event == JSLogEvent.PRE_SCRIPT_SUCCESS:
            pass
        elif record.event == JSLogEvent.PRE_SCRIPT_FAILURE:
            # If the pre script failed, then the job failed
            self.state = JobState.FAILED
        elif record.event == JSLogEvent.SUBMIT:
            self.state = JobState.QUEUED
        elif record.event == JSLogEvent.EXECUTE:
            self.state = JobState.RUNNING
        elif record.event == JSLogEvent.JOB_TERMINATED:
            pass
        elif record.event == JSLogEvent.JOB_SUCCESS:
            # If no post script, then job was successful,
            # otherwise ignore it because the post script will run
            if not self.postscript:
                self.state = JobState.SUCCESSFUL
        elif record.event == JSLogEvent.JOB_FAILURE:
            # If no post script, then job failed,
            # otherwise ignore it because the post script will run
            if not self.postscript:
                self.state = JobState.FAILED
        elif record.event == JSLogEvent.POST_SCRIPT_STARTED:
            self.state = JobState.POSTSCRIPT
        elif record.event ==JSLogEvent.POST_SCRIPT_TERMINATED:
            pass
        elif record.event == JSLogEvent.POST_SCRIPT_SUCCESS:
            self.state = JobState.SUCCESSFUL
        elif record.event == JSLogEvent.POST_SCRIPT_FAILURE:
            self.state = JobState.FAILED
        else:
            raise DAGException("Unknown job state log event", record.event)

        # When job succeeds, mark ready children
        if self.state == JobState.SUCCESSFUL:
            for c in self.children:
                if c.state != JobState.UNREADY:
                    raise DAGException("Child %s of newly successful job %s should be UNREADY" % (c.name, self.name))

                ready = True
                for p in c.parents:
                    if p.state != JobState.SUCCESSFUL:
                        ready = False
                        break

                if ready:
                    c.state = JobState.READY

    def __str__(self):
        return "Job(%s, %s, %f)" % (self.name, self.state, self.runtime)

    def clone(self):
        newjob = Job(self.name)
        newjob.runtime = self.runtime
        newjob.state = self.state
        newjob.prescript = self.prescript
        newjob.postscript = self.postscript
        return newjob

class DAG(object):
    def __init__(self, jobs):
        self.jobs = jobs
        self.lock = threading.Lock()

    def process_jslog_record(self, record):
        self.lock.acquire()
        job_name = record.job_name
        job = self.jobs[job_name]
        job.process_jslog_record(record)
        self.lock.release()

    def print_stats(self):
        self.lock.acquire()
        stats = {}
        for job_name in self.jobs:
            job = self.jobs[job_name]
            if job.state not in stats:
                stats[job.state] = 0
            stats[job.state] += 1

        log.info("Workflow State: %s", stats)
        self.lock.release()

    def clone(self):
        self.lock.acquire()

        jobs = {}
        edges = []

        # Clone the jobs
        for name in self.jobs:
            job = self.jobs[name]

            # Clone the job
            jobs[name] = job.clone()

            # Store the edges
            for child in job.children:
                edges.append((name, child.name))

        # Clone the edges
        for pname, cname in edges:
            parent = jobs[pname]
            child = jobs[cname]
            parent.children.append(child)
            child.parents.append(parent)

        dag = DAG(jobs)

        self.lock.release()

        return dag

class DAGException(Exception): pass

def parse_submit_file(submit_file):
    record = {}

    with open(submit_file, "r") as f:
        for l in f:
            l = l.strip()
            if l.startswith("+pegasus_job_runtime"):
                rec = l.split(" = ")
                record["pegasus_job_runtime"] = float(rec[1])

    return record

def parse_dag(dag_file):
    log.info("Parsing DAG...")

    jobs = {}

    with open(dag_file, "r") as f:
        for l in f:
            l = l.strip()
            rec = l.split()
            if l.startswith("JOB"):
                job_name = rec[1]
                submit_file = rec[2]
                rec = parse_submit_file(submit_file)
                j = Job(job_name)
                j.runtime = rec.get("pegasus_job_runtime", 0.0)
                jobs[job_name] = j
                log.debug("Parsed job: %s", job_name)
            elif l.startswith("PARENT"):
                # XXX This isn't strictly correct because DAGMan allows
                # multiple parents and children, but Pegasus doesn't use
                # that feature
                p = rec[1]
                c = rec[3]
                parent = jobs[p]
                child = jobs[c]
                parent.children.append(child)
                child.parents.append(parent)
                log.debug("Parsed edge: %s -> %s", p, c)
            elif l.startswith("SCRIPT"):
                # Just record the script type. We need to know if a job
                # has a PRE/POST script when we process the jobstate log
                # events.
                stype = rec[1]
                job_name = rec[2]
                job = jobs[job_name]
                if stype == "POST":
                    job.postscript = True
                elif stype == "PRE":
                    job.prescript = True
                else:
                    raise DAGException("Unrecognized script type: %s" % stype)
            elif l.startswith("RETRY"):
                # Shadow queue doesn't care about RETRIES
                pass
            elif l.startswith("MAXJOBS"):
                pass
            elif len(l) == 0 or l[0] == "#":
                # Skip blank lines and comments
                pass
            else:
                raise DAGException("Unrecognized record", l)

    # Mark workflow roots as ready
    for job_name in jobs:
        job = jobs[job_name]
        if len(job.parents) == 0:
            job.state = JobState.READY

    dag = DAG(jobs)

    log.info("Parsed DAG with %d jobs", len(jobs))

    return dag


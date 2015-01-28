package jt;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.Iterator;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.IterativeReducer;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;

public class KMeansReduceR extends MapReduceBase implements
		IterativeReducer<IntWritable, Text, IntWritable, Text> {
	private int iteration;
	private Date start;
	private int threshold;

	public void configure(JobConf job) {
		this.iteration = 0;
		this.start = new Date();
		this.threshold = job.getInt("kmeans.threshold", 0);
	}

	public void reduce(IntWritable key, Iterator<Text> values,
			OutputCollector<IntWritable, Text> output, Reporter report)
			throws IOException {
		LastFMUserR base = new LastFMUserR(key.get(), "");
		while (values.hasNext()) {
			String data = ((Text) values.next()).toString();

			if (KmeanR.COMBINE) {
				LastFMUserR curr = new LastFMUserR(key.get(), data, true);
				base.addinred(curr);
			} else {
				LastFMUserR curr = new LastFMUserR(key.get(), data);
				base.add(curr);
			}

		}

		output.collect(key, new Text(base.getArtists(this.threshold)));
		System.out.println(key + "\t" + base.getArtists(this.threshold));
	}

	public void iterate() {
		this.iteration += 1;
		Date current = new Date();
		long passed = (current.getTime() - this.start.getTime()) / 1000L;

		System.out.println("iteration " + this.iteration + " timepassed "
				+ passed);
	}
}
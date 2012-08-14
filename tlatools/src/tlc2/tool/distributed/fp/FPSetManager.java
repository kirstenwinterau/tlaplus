// Copyright (c) 2003 Compaq Corporation.  All rights reserved.
// Portions Copyright (c) 2003 Microsoft Corporation.  All rights reserved.
// Last modified on Mon 30 Apr 2007 at 13:13:43 PST by lamport
//      modified on Wed Jan 10 00:09:44 PST 2001 by yuanyu

package tlc2.tool.distributed.fp;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.rmi.RemoteException;
import java.rmi.UnmarshalException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import tlc2.util.BitVector;
import tlc2.util.LongVec;
import util.ToolIO;

/**
 * @author Simon Zambrovski
 * @version $Id$
 */
@SuppressWarnings("serial")
public abstract class FPSetManager implements Serializable, IFPSetManager {

	protected long mask = 0x7FFFFFFFFFFFFFFFL;
	/**
	 * A list of pairs. A pair is a remote reference and its corresponding
	 * hostname. The name is cached locally to report it correctly in the error
	 * case, where it's impossible to call {@link FPSetRMI#getHostname}.
	 */
	protected List<FPSets> fpSets;

	// SZ Jul 13, 2009: moved from FPSetRMI
	public static int Port = 10998; // port # for fpset server

	public FPSetManager() {
		 this(new ArrayList<FPSets>());
	}
	
	public FPSetManager(List<FPSets> fpSets) {
		this.fpSets = fpSets;
	}

	public FPSetManager(FPSetRMI fpSet) {
		this();
		this.fpSets.add(new FPSets(fpSet, fpSet.toString()));
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#numOfServers()
	 */
	public final int numOfServers() {
		return this.fpSets.size();
	}

	private final int reassign(int i) {
		int next = (i + 1) % this.fpSets.size();
		while (next != i) {
			FPSets fpSet = this.fpSets.get(next);
			if (fpSet != null) {
				for (int j = i; j < next; j++) {
					this.fpSets.add(j, fpSet);
				}
				return next;
			}
			next = (next + 1) % this.fpSets.size();
		}
		return -1;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#close(boolean)
	 */
	public final void close(boolean cleanup) throws IOException {
		FPSets curr = null;
		int len = this.fpSets.size();
		int idx = 0, lidx = 0;

		for (idx = 0; idx < len; idx++) {
			curr = this.fpSets.get(idx);
			if (curr != null)
				break;
		}
		if (curr == null)
			return;

		for (lidx = len - 1; lidx > idx; lidx--) {
			FPSets last = this.fpSets.get(lidx);
			if (last != null && last != curr)
				break;
		}
		for (int i = idx + 1; i <= lidx; i++) {
			FPSets next = this.fpSets.get(i);
			if (next != null && next != curr) {
				try {
					curr.exit(cleanup);
				} catch (UnmarshalException e) {
					// happens when the DiskFPSet closes it calls System.exit
				} catch (Exception e) {
					e.printStackTrace();
				}
				curr = next;
			}
		}
		if (curr != null) {
			try {
				curr.exit(cleanup);
			} catch (UnmarshalException e) {
				// happens when the DiskFPSet closes it calls System.exit
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private final String getHostName() {
		String hostname = "Unknown";
		try {
			hostname = InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return hostname;
	}

	protected int getIndex(long fp) {
		return (int) ((fp & mask) % this.fpSets.size());
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#put(long)
	 */
	public final boolean put(long fp) {
		int fpIdx = getIndex(fp);
		while (true) {
			try {
				return this.fpSets.get(fpIdx).put(fp);
			} catch (Exception e) {
				System.out.println("Warning: Failed to connect from "
						+ this.getHostName() + " to the fp server at "
						+ this.fpSets.get(fpIdx).getHostname() + ".\n" + e.getMessage());
				e.printStackTrace();
				if (this.reassign(fpIdx) == -1) {
					System.out
							.println("Warning: there is no fp server available.");
					return false;
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#putBlock(tlc2.util.LongVec[])
	 */
	public final BitVector[] putBlock(LongVec[] fps) {
		int len = this.fpSets.size();
		BitVector[] res = new BitVector[len];
		for (int i = 0; i < len; i++) {
			try {
				res[i] = this.fpSets.get(i).putBlock(fps[i]);
			} catch (Exception e) {
				System.out.println("Warning: Failed to connect from "
						+ this.getHostName() + " to the fp server at "
						+ this.fpSets.get(i).getHostname() + ".\n" + e.getMessage());
				e.printStackTrace();
				if (this.reassign(i) == -1) {
					System.out
							.println("Warning: there is no fp server available.");
				}
				res[i] = new BitVector(fps[i].size());
				res[i].set(0, fps[i].size() - 1);
			}
		}
		return res;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.fp.IFPSetManager#putBlock(tlc2.util.LongVec[], java.util.concurrent.ExecutorService)
	 */
	public BitVector[] putBlock(final LongVec[] fps, final ExecutorService executorService) {
		final int len = this.fpSets.size();
		
		// Synchronize this and nested threads
		final CountDownLatch cdl = new CountDownLatch(len);
		
		final List<Future<BitVector>> futures = new ArrayList<Future<BitVector>>();
		for (int i = 0; i < len; i++) {
			futures.add(executorService.submit(new PutBlockCallable(cdl, fpSets.get(i), fps, i)));
		}
		
		// Wait for all threads to finish
		try {
			cdl.await();
		} catch (InterruptedException e) {
			e.printStackTrace();
			// not expected to happen
		}
		
		// Convert and return result
		final BitVector[] res = new BitVector[len];
		for (int i = 0; i < res.length; i++) {
			try {
				res[i] = futures.get(i).get();
			} catch (InterruptedException e) {
				e.printStackTrace();
				// not expected to happen
			} catch (ExecutionException e) {
				e.printStackTrace();
				// not expected to happen
			}
		}
		return res;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#containsBlock(tlc2.util.LongVec[])
	 */
	public final BitVector[] containsBlock(LongVec[] fps) {
		int len = this.fpSets.size();
		BitVector[] res = new BitVector[len];
		for (int i = 0; i < len; i++) {
			try {
				res[i] = this.fpSets.get(i).containsBlock(fps[i]);
			} catch (Exception e) {
				System.out.println("Warning: Failed to connect from "
						+ this.getHostName() + " to the fp server at "
						+ this.fpSets.get(i).getHostname() + ".\n" + e.getMessage());
				e.printStackTrace();
				if (this.reassign(i) == -1) {
					System.out
							.println("Warning: there is no fp server available.");
				}
				res[i] = new BitVector(fps[i].size());
				res[i].set(0, fps[i].size() - 1);
			}
		}
		return res;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.fp.IFPSetManager#containsBlock(tlc2.util.LongVec[], java.util.concurrent.ExecutorService)
	 */
	public BitVector[] containsBlock(final LongVec[] fps, final ExecutorService executorService) {
		final int len = this.fpSets.size();
		
		// Synchronize this and nested threads
		final CountDownLatch cdl = new CountDownLatch(len);
		
		final List<Future<BitVector>> futures = new ArrayList<Future<BitVector>>();
		for (int i = 0; i < len; i++) {
			futures.add(executorService.submit(new ContainsBlockCallable(cdl, fpSets.get(i), fps, i)));
		}
		
		// Wait for all threads to finish
		try {
			cdl.await();
		} catch (InterruptedException e) {
			e.printStackTrace();
			// not expected to happen
		}
		
		// Convert and return result
		final BitVector[] res = new BitVector[len];
		for (int i = 0; i < res.length; i++) {
			try {
				res[i] = futures.get(i).get();
			} catch (InterruptedException e) {
				e.printStackTrace();
				// not expected to happen
			} catch (ExecutionException e) {
				e.printStackTrace();
				// not expected to happen
			}
		}
		return res;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#checkFPs()
	 */
	public double checkFPs() {
		final int len = this.fpSets.size();
		
		// Synchronize this and nested threads
		final CountDownLatch cdl = new CountDownLatch(len);

		// Start checkFP on all FPSets concurrently
		// (checkFPs scans the full set sequentially!)
		FPCheckerRunnable[] runnables = new FPCheckerRunnable[len];
		for (int i = 0; i < len; i++) {
			final FPSetRMI fpSetRMI = this.fpSets.get(i).getFpset();
			runnables[i] = new FPCheckerRunnable(fpSetRMI, cdl);
			final Thread t = new Thread(runnables[i]);
			t.start();
		}

		// Wait for all threads to finish
		try {
			cdl.await();
		} catch (InterruptedException e) {
			e.printStackTrace();
			// not expected to happen
		}
		
		// Return minimum value
		double res = Double.MAX_VALUE;
		for (int i = 0; i < runnables.length; i++) {
			FPCheckerRunnable fpCheckerRunnable = runnables[i];
			res = Math.min(res, fpCheckerRunnable.getResult());
		}
		return res;
	}
	
	public class FPCheckerRunnable implements Runnable {
		private final FPSetRMI fpSetRMI;
		private final CountDownLatch cdl;
		private double distance;
		
		public FPCheckerRunnable(FPSetRMI fpSetRMI, CountDownLatch cdl) {
			this.fpSetRMI = fpSetRMI;
			this.cdl = cdl;
		}

		/* (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		public void run() {
			try {
				distance = fpSetRMI.checkFPs();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				cdl.countDown();
			}
		}
		
		public double getResult() {
			return distance;
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#size()
	 */
	public final long size() {
		int len = this.fpSets.size();
		long res = 0;
		for (int i = 0; i < len; i++) {
			try {
				res += this.fpSets.get(i).size();
			} catch (Exception e) {
				System.out.println("Warning: Failed to connect from "
						+ this.getHostName() + " to the fp server at "
						+ this.fpSets.get(i).getHostname() + ".\n" + e.getMessage());
				e.printStackTrace();
				if (this.reassign(i) == -1) {
					System.out
							.println("Warning: there is no fp server available.");
				}
			}
		}
		return res;
	}
	
	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#getStatesSeen()
	 */
	public final long getStatesSeen() {
		int len = this.fpSets.size();
		long res = 1; // the initial state
		for (int i = 0; i < len; i++) {
			try {
				res += this.fpSets.get(i).getStatesSeen();
			} catch (Exception e) {
				System.out.println("Warning: Failed to connect from "
						+ this.getHostName() + " to the fp server at "
						+ this.fpSets.get(i).getHostname() + ".\n" + e.getMessage());
				e.printStackTrace();
				if (this.reassign(i) == -1) {
					System.out
							.println("Warning: there is no fp server available.");
				}
			}
		}
		return res;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#getMask()
	 */
	public long getMask() {
		return mask;
	}

	private final void chkptInner(String fname, boolean chkpt)
			throws InterruptedException {
		int len = this.fpSets.size();
		Checkpoint[] chkpts = new Checkpoint[len];
		FPSets curr = null;
		int cnt = 0, idx = 0, lidx = 0;

		for (idx = 0; idx < len; idx++) {
			curr = this.fpSets.get(idx);
			if (curr != null) {
				chkpts[cnt] = new Checkpoint(idx, fname, chkpt);
				chkpts[cnt].run();
				cnt++;
				break;
			}
		}
		if (curr == null)
			return;

		for (lidx = len - 1; lidx > idx; lidx--) {
			FPSets last = this.fpSets.get(lidx);
			if (last != null && last != curr)
				break;
		}

		for (int i = idx + 1; i <= lidx; i++) {
			FPSets next = this.fpSets.get(i);
			if (next != null && next != curr) {
				curr = next;
				chkpts[cnt] = new Checkpoint(i, fname, chkpt);
				chkpts[cnt].run();
				cnt++;
			}
		}

		for (int i = 0; i < cnt; i++) {
			chkpts[i].join();
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#checkpoint(java.lang.String)
	 */
	public final void checkpoint(String fname) throws InterruptedException {
		chkptInner(fname, true);
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.distributed.IFPSetManager#recover(java.lang.String)
	 */
	public final void recover(String fname) throws InterruptedException {
		chkptInner(fname, false);
	}

	final class Checkpoint extends Thread {
		int hostIndex;
		String filename;
		boolean isChkpt;

		public Checkpoint(int index, String fname, boolean chkpt) {
			this.hostIndex = index;
			this.filename = fname;
			this.isChkpt = chkpt;
		}

		public void run() {
			try {
				if (this.isChkpt) {
					fpSets.get(this.hostIndex).beginChkpt(this.filename);
					fpSets.get(this.hostIndex).commitChkpt(this.filename);
				} else {
					fpSets.get(this.hostIndex).recover(this.filename);
				}
			} catch (IOException e) {
				ToolIO.out
						.println("Error: Failed to checkpoint the fingerprint server at "
								+ fpSets.get(this.hostIndex).getHostname()
								+ ". This server might be down.");
			}
		}
	}
	
	public static class FPSets implements Serializable {
		private final String hostname;
		private final FPSetRMI fpset;

		public FPSets(FPSetRMI fpset, String hostname) {
			this.fpset = fpset;
			this.hostname = hostname;
		}

		public void exit(boolean cleanup) throws IOException {
			fpset.exit(cleanup);
		}

		public void recover(String filename) throws IOException {
			fpset.recover(filename);
		}

		public void commitChkpt(String filename) throws IOException {
			fpset.commitChkpt(filename);
		}

		public void beginChkpt(String filename) throws IOException {
			fpset.beginChkpt(filename);
		}

		public long getStatesSeen() throws RemoteException {
			return fpset.getStatesSeen();
		}

		public long size() throws IOException {
			return fpset.size();
		}

		public BitVector containsBlock(LongVec longVec) throws IOException {
			return fpset.containsBlock(longVec);
		}

		public BitVector putBlock(LongVec longVec) throws IOException {
			return fpset.putBlock(longVec);
		}

		public boolean put(long fp) throws IOException {
			return fpset.put(fp);
		}

		public String getHostname() {
			return hostname;
		}

		public FPSetRMI getFpset() {
			return fpset;
		}
	}
	
	public abstract class FPSetManagerCallable implements Callable<BitVector> {
		protected final FPSets fpset;
		protected final LongVec[] fps;
		protected final int index;
		protected final CountDownLatch cdl;
		public FPSetManagerCallable(CountDownLatch cdl, FPSets fpset, LongVec[] fps, int index) {
			this.cdl = cdl;
			this.fpset = fpset;
			this.fps = fps;
			this.index = index;
		}
		
		//TODO Does this behave correctly if multiple threads execute it concurrently?
		protected BitVector reassign(Exception e) {
			System.out.println("Warning: Failed to connect from "
					+ getHostName() + " to the fp server at "
					+ fpset.getHostname() + ".\n" + e.getMessage());
			if (FPSetManager.this.reassign(index) == -1) {
				System.out
				.println("Warning: there is no fp server available.");
			}
			BitVector bitVector = new BitVector(fps[index].size());
			bitVector.set(0, fps[index].size() - 1);
			return bitVector;
		}
	}
	
	public class PutBlockCallable extends FPSetManagerCallable {
		public PutBlockCallable(CountDownLatch cdl, FPSets fpset, LongVec[] fps, int index) {
			super(cdl, fpset, fps, index);
		}
		public BitVector call() throws Exception {
			try {
				return fpset.putBlock(fps[index]);
			} catch (Exception e) {
				return reassign(e);
			} finally {
				cdl.countDown();
			}
		}
	}
	
	public class ContainsBlockCallable extends FPSetManagerCallable {
		public ContainsBlockCallable(CountDownLatch cdl, FPSets fpset, LongVec[] fps, int index) {
			super(cdl, fpset, fps, index);
		}
		public BitVector call() throws Exception {
			try {
				return fpset.containsBlock(fps[index]);
			} catch (Exception e) {
				return reassign(e);
			} finally {
				cdl.countDown();
			}
		}
	}
}
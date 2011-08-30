/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.rapleaf.hank.partition_server;

import com.rapleaf.hank.config.PartitionServerConfigurator;
import com.rapleaf.hank.coordinator.*;
import com.rapleaf.hank.storage.Deleter;
import com.rapleaf.hank.storage.StorageEngine;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Manages the domain update process.
 */
class UpdateManager implements IUpdateManager {
  private static final int TERMINATION_CHECK_TIMEOUT_MS = 1000;
  private static final Logger LOG = Logger.getLogger(UpdateManager.class);

  private final class UpdateToDo implements Runnable {
    private final StorageEngine engine;
    private final int partitionNumber;
    private final Queue<Throwable> exceptionQueue;
    private final int toDomainVersion;
    private final HostDomainPartition partition;
    private final String domainName;
    private final int toDomainGroupVersion;
    private final Set<Integer> excludeVersions;

    public UpdateToDo(StorageEngine engine,
                      int partitionNumber,
                      Queue<Throwable> exceptionQueue,
                      int toDomainVersion,
                      HostDomainPartition partition,
                      String domainName,
                      int toDomainGroupVersion,
                      Set<Integer> excludeVersions) {
      this.engine = engine;
      this.partitionNumber = partitionNumber;
      this.exceptionQueue = exceptionQueue;
      this.toDomainVersion = toDomainVersion;
      this.partition = partition;
      this.domainName = domainName;
      this.toDomainGroupVersion = toDomainGroupVersion;
      this.excludeVersions = excludeVersions;
    }

    @Override
    public void run() {
      try {
        LOG.info(String.format("%s part %d to version %d starting (%s)", domainName, partitionNumber, toDomainVersion, engine.toString()));
        engine.getUpdater(configurator, partitionNumber).update(toDomainVersion, excludeVersions);
        partition.setCurrentDomainGroupVersion(toDomainGroupVersion);
        partition.setUpdatingToDomainGroupVersion(null);
        LOG.info(String.format("UpdateToDo %s part %d completed.", engine.toString(), partitionNumber));
      } catch (Throwable e) {
        LOG.fatal("Failed to complete an UpdateToDo!", e);
        exceptionQueue.add(e);
      }
    }
  }

  private final PartitionServerConfigurator configurator;
  private final Host hostConfig;
  private final RingGroup ringGroupConfig;
  private final Ring ringConfig;

  public UpdateManager(PartitionServerConfigurator configurator, Host hostConfig, RingGroup ringGroupConfig, Ring ringConfig) throws IOException {
    this.configurator = configurator;
    this.hostConfig = hostConfig;
    this.ringGroupConfig = ringGroupConfig;
    this.ringConfig = ringConfig;
  }

  public void update() throws IOException {
    ThreadFactory factory = new ThreadFactory() {
      private int x = 0;

      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, "Updater Thread Pool Thread #" + ++x);
      }
    };

    ExecutorService executor = new ThreadPoolExecutor(
        configurator.getNumConcurrentUpdates(),
        configurator.getNumConcurrentUpdates(),
        1, TimeUnit.DAYS,
        new LinkedBlockingQueue<Runnable>(),
        factory);
    Queue<Throwable> exceptionQueue = new LinkedBlockingQueue<Throwable>();

    DomainGroup domainGroup = ringGroupConfig.getDomainGroup();
    for (DomainGroupVersionDomainVersion dgvdv : domainGroup.getLatestVersion().getDomainVersions()) {
      Domain domain = dgvdv.getDomain();

      Set<Integer> excludeVersions = new HashSet<Integer>();
      for (DomainVersion dv : domain.getVersions()) {
        if (dv.isDefunct()) {
          excludeVersions.add(dv.getVersionNumber());
        }
      }

      StorageEngine engine = domain.getStorageEngine();

      int domainId = domainGroup.getDomainId(domain.getName());
      for (HostDomainPartition part : hostConfig.getDomainById(domainId).getPartitions()) {
        if (part.isDeletable()) {
          Deleter deleter = engine.getDeleter(configurator, part.getPartNum());
          deleter.delete();
          part.delete();
        } else if (part.getUpdatingToDomainGroupVersion() != null) {
          LOG.debug(String.format("Configuring update task for group-%s/ring-%d/domain-%s/part-%d from %d to %d",
              ringGroupConfig.getName(),
              ringConfig.getRingNumber(),
              domain.getName(),
              part.getPartNum(),
              part.getCurrentDomainGroupVersion(),
              part.getUpdatingToDomainGroupVersion()));
          executor.execute(new UpdateToDo(engine,
              part.getPartNum(),
              exceptionQueue,
              dgvdv.getVersionNumber(),
              part,
              domain.getName(),
              part.getUpdatingToDomainGroupVersion(),
              excludeVersions));
        }
      }
    }

    try {
      // Wait for all tasks to finish
      boolean terminated = false;
      executor.shutdown();
      while (!terminated) {
        LOG.debug("Waiting for update executor to complete...");
        terminated = executor.awaitTermination(TERMINATION_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      }
      // Detect failed tasks
      if (!exceptionQueue.isEmpty()) {
        LOG.fatal(String.format("%d exceptions encountered while running UpdateToDo:", exceptionQueue.size()));
        int i = 0;
        for (Throwable t : exceptionQueue) {
          LOG.fatal(String.format("Exception %d/%d:", ++i, exceptionQueue.size()), t);
        }
        throw new RuntimeException("Failed to complete update!");
      }
    } catch (InterruptedException e) {
      LOG.debug("Interrupted while waiting for update to complete. Terminating.");
    }
  }
}

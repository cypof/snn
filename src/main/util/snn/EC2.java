package snn;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;

/**
 * Manages EC2 instances.
 */
public class EC2 {
  private static final String USER = System.getProperty("user.name");
  private static final String NAME = USER + "-SNN-Cloud";

  public int boxes;
  public String region = "us-east-1";
  //public String image = "ami-3565305c"; // Amazon Linux, x64, Instance-Store, US East N. Virginia
  //public String image = "ami-dfbfe4b6"; // Amazon Linux, x64, HVM, Instance-Store, US East N. Virginia
  //public String image = "ami-e1357b88"; // Ubuntu Raring 13.04 amd64
  public String image = "ami-09614460";   // Ubuntu Raring 13.04 amd64 HVM
  //public String type = "m1.xlarge";
  public String type = "cc2.8xlarge"; // HPC
  public String securityGroup = "ssh"; // "default";
  public boolean confirm = true;

  public final List<String> publicIPs = new ArrayList<String>();
  public final List<String> privateIPs = new ArrayList<String>();

//@formatter:off
  static String cloudConfig = "" + // TODO try Amazon AMI "Enhanced Networking"
      l("#cloud-config") +
      l("users:") +
      l("  - name: " + USER) +
      l("    sudo: ALL=(ALL) NOPASSWD:ALL") +
      l("    ssh-authorized-keys:") +
      l("      - " + pubKey()) +
      l("    shell: /bin/bash") +
      l("") +
      l("runcmd:") +
      l("  - iptables -I INPUT -p tcp --dport 22 -j DROP") +
//      l("  - echo 'fs.file-max = 524288' > /etc/sysctl.d/increase-max-fd.conf") +
//      l("  - sysctl -w fs.file-max=524288") +
//      l("  - echo '* soft nofile 524288' > /etc/security/limits.d/increase-max-fd-soft.conf") +
//      l("  - echo '* hard nofile 524288' > /etc/security/limits.d/increase-max-fd-hard.conf") +
//      l("  - apt-get update") +
      l("  - apt-get -y install openjdk-7-jdk") +
//      l("  - apt-get -y install openvpn") +
      l("  - iptables -D INPUT 1") +
      l("");
  static String l(String line) { return line + "\n"; }
  //@formatter:on

  /**
   * Create or terminate EC2 instances. Uses their Name tag to find existing ones.
   */
  public void resize() throws Exception {
    PropertiesCredentials credentials;
    try {
      credentials = new PropertiesCredentials(new File("AwsCredentials.properties"));
    } catch( Exception ex ) {
      System.out.println("Please provide your AWS credentials in './AwsCredentials.properties'");
      System.out.println("File format is:");
      System.out.println("accessKey=XXXXXXXXXXXXXXXXXXXX");
      System.out.println("secretKey=XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
      throw ex;
    }
    AmazonEC2Client ec2 = new AmazonEC2Client(credentials);
    ec2.setEndpoint("ec2." + region + ".amazonaws.com");
    DescribeInstancesResult describeInstancesResult = ec2.describeInstances();
    List<Reservation> reservations = describeInstancesResult.getReservations();
    List<Instance> instances = new ArrayList<Instance>();

    for( Reservation reservation : reservations ) {
      for( Instance instance : reservation.getInstances() ) {
        String ip = ip(instance);
        if( ip != null ) {
          String name = null;
          if( instance.getTags().size() > 0 )
            name = instance.getTags().get(0).getValue();
          if( NAME.equals(name) )
            instances.add(instance);
        }
      }
    }
    System.out.println("Found " + instances.size() + " EC2 instances for user " + USER);

    if( instances.size() > boxes ) {
      for( int i = 0; i < instances.size() - boxes; i++ ) {
        // TODO terminate?
      }
    } else if( instances.size() < boxes ) {
      int launchCount = boxes - instances.size();
      System.out.println("Creating " + launchCount + " EC2 instances.");
      if( confirm ) {
        System.out.println("Please confirm [y/n]");
        String s = Utils.readConsole();
        if( s == null || !s.equalsIgnoreCase("y") )
          throw new Exception("Aborted");
      }

      CreatePlacementGroupRequest group = new CreatePlacementGroupRequest();
      group.withGroupName(USER);
      group.withStrategy(PlacementStrategy.Cluster);
      try {
        ec2.createPlacementGroup(group);
      } catch( AmazonServiceException ex ) {
        if( !"InvalidPlacementGroup.Duplicate".equals(ex.getErrorCode()) )
          throw ex;
      }

      RunInstancesRequest run = new RunInstancesRequest();
      run.withInstanceType(type);
      run.withImageId(image);
      run.withMinCount(launchCount).withMaxCount(launchCount);
      run.withSecurityGroupIds(securityGroup);
      Placement placement = new Placement();
      placement.setGroupName(USER);
      run.withPlacement(placement);
      BlockDeviceMapping map = new BlockDeviceMapping();
      map.setDeviceName("/dev/sdb");
      map.setVirtualName("ephemeral0");
      run.withBlockDeviceMappings(map);
      run.withUserData(Base64.encodeToString(cloudConfig.getBytes(), true));

      RunInstancesResult runRes = ec2.runInstances(run);
      ArrayList<String> ids = new ArrayList<String>();
      for( Instance instance : runRes.getReservation().getInstances() )
        ids.add(instance.getInstanceId());

      List<Instance> created = wait(ec2, ids);
      System.out.println("Created " + created.size() + " EC2 instances.");
      instances.addAll(created);
    }

    publicIPs.clear();
    privateIPs.clear();;
    for( int i = 0; i < boxes; i++ ) {
      publicIPs.add(instances.get(i).getPublicIpAddress());
      privateIPs.add(instances.get(i).getPrivateIpAddress());
    }
    System.out.println("EC2 public IPs: " + Utils.join(' ', publicIPs.toArray()));
    System.out.println("EC2 private IPs: " + Utils.join(' ', privateIPs.toArray()));
  }

  private static String pubKey() {
    BufferedReader r = null;
    try {
      String pub = System.getProperty("user.home") + "/.ssh/id_rsa.pub";
      r = new BufferedReader(new FileReader(new File(pub)));
      return r.readLine();
    } catch( IOException e ) {
      throw new RuntimeException(e);
    } finally {
      if( r != null ) {
        try {
          r.close();
        } catch( IOException e ) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private List<Instance> wait(AmazonEC2Client ec2, List<String> ids) {
    System.out.println("Establishing ssh connections, make sure security group '" //
        + securityGroup + "' allows incoming TCP 22.");
    boolean tagsDone = false;
    for( ;; ) {
      try {
        if( !tagsDone ) {
          CreateTagsRequest createTagsRequest = new CreateTagsRequest();
          createTagsRequest.withResources(ids).withTags(new Tag("Name", NAME));
          ec2.createTags(createTagsRequest);
          tagsDone = true;
        }
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.withInstanceIds(ids);
        DescribeInstancesResult result = ec2.describeInstances(request);
        List<Reservation> reservations = result.getReservations();
        List<Instance> instances = new ArrayList<Instance>();
        for( Reservation reservation : reservations )
          for( Instance instance : reservation.getInstances() )
            if( ip(instance) != null )
              instances.add(instance);
        if( instances.size() == ids.size() ) {
          // Try to connect to SSH port on each box
          if( canConnect(instances) )
            return instances;
        }
      } catch( AmazonServiceException _ ) {
        // Ignore and retry
      }
      try {
        Thread.sleep(500);
      } catch( InterruptedException e ) {
        throw new RuntimeException(e);
      }
    }
  }

  private static String ip(Instance instance) {
    String ip = instance.getPublicIpAddress();
    if( ip != null && ip.length() != 0 )
      if( instance.getState().getName().equals("running") )
        return ip;
    return null;
  }

  private static boolean canConnect(List<Instance> instances) {
    for( Instance instance : instances ) {
      try {
        String ssh = "ssh -q" + Host.SSH_OPTS + " " + instance.getPublicIpAddress();
        Process p = Runtime.getRuntime().exec(ssh + " exit");
        if( p.waitFor() != 0 )
          return false;
      } catch( Exception e ) {
        return false;
      } finally {
      }
    }
    return true;
  }
}

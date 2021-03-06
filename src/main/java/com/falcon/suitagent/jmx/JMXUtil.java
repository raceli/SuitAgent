/*
 * www.msxf.com Inc.
 * Copyright (c) 2017 All Rights Reserved
 */
package com.falcon.suitagent.jmx;
//             ,%%%%%%%%,
//           ,%%/\%%%%/\%%
//          ,%%%\c "" J/%%%
// %.       %%%%/ o  o \%%%
// `%%.     %%%%    _  |%%%
//  `%%     `%%%%(__Y__)%%'
//  //       ;%%%%`\-/%%%'
// ((       /  `%%%%%%%'
//  \\    .'          |
//   \\  /       \  | |
//    \\/攻城狮保佑) | |
//     \         /_ | |__
//     (___________)))))))                   `\/'
/*
 * 修订记录:
 * long.qian@msxf.com 2017-08-04 14:47 创建
 */

import com.falcon.suitagent.config.AgentConfiguration;
import com.falcon.suitagent.util.*;
import com.falcon.suitagent.vo.docker.ContainerProcInfoToHost;
import com.falcon.suitagent.vo.jmx.JavaExecCommandInfo;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.falcon.suitagent.jmx.AbstractJmxCommand.getJMXConfigValueForLinux;
import static com.falcon.suitagent.jmx.AbstractJmxCommand.getJMXConfigValueForMac;

/**
 * @author long.qian@msxf.com
 */
@Slf4j
public class JMXUtil {

    /**
     * 获取本地是否已开启指定的JMX服务
     * @param serverName
     * @return
     */
    public static boolean hasJMXServerInLocal(String serverName){
        if(!StringUtils.isEmpty(serverName)){
            return !getVmDescByServerName(serverName).isEmpty();
        }
        return false;
    }

    /**
     * 获取物理机容器中运行的指定Java程序的命令启动字符串
     * @param serverName
     * Java程序服务名，指定为*则返回所有
     * @return
     */
    public static List<JavaExecCommandInfo> getHostJavaCommandInfosFromContainer(String serverName) throws Exception {
        String cacheKey = "hostJavaCommandInfosFromContainer" + serverName;
        List<JavaExecCommandInfo> javaExecCommandInfos = (List<JavaExecCommandInfo>) CacheByTimeUtil.getCache(cacheKey);
        if (javaExecCommandInfos != null){
            return javaExecCommandInfos;
        }
        javaExecCommandInfos = new ArrayList<>();
        if (StringUtils.isNotEmpty(serverName) && AgentConfiguration.INSTANCE.isDockerRuntime()){
            //仅为Docker容器运行环境时有效
            List<ContainerProcInfoToHost> containerProcInfoToHosts = DockerUtil.getAllHostContainerProcInfos();
            for (ContainerProcInfoToHost containerProcInfoToHost : containerProcInfoToHosts) {
                String appName = DockerUtil.getJavaContainerAppName(containerProcInfoToHost.getContainerId());
                if (appName != null){//只采集指定了appName的容器(必须通过docker run命令的-e参数执行应用名，例如 docker run -e "appName=suitAgent")
                    //添加id到appName
                    appName = appName + "-" + containerProcInfoToHost.getContainerId().substring(0,12);
                    String tmpDir = containerProcInfoToHost.getProcPath() + "/" + "tmp";
                    List<String> pidFiles = getPidListFromTmp(tmpDir);
                    if (!pidFiles.isEmpty()) {
                        String containerIp = DockerUtil.getContainerIp(containerProcInfoToHost.getContainerId());
                        if (pidFiles.size() == 1){  //容器中只有一个Java进程，直接用appName命名
                            String cmd = HexUtil.filter(FileUtil.getTextFileContent(containerProcInfoToHost.getProcPath() + "/proc/" + pidFiles.get(0) + "/cmdline")," ");
                            if ("*".equals(serverName)){
                                javaExecCommandInfos.add(new JavaExecCommandInfo(appName,containerIp,cmd));
                            }else if (hasContainsServerNameForContainer(cmd,serverName)){
                                javaExecCommandInfos.add(new JavaExecCommandInfo(appName,containerIp,cmd));
                            }
                        }else if (pidFiles.size() > 1){ //容器中若有多个java进程，但一个容器只有一个appName，所以用JMX Port命名
                            for (String pidFile : pidFiles) {
                                String cmd = HexUtil.filter(FileUtil.getTextFileContent(containerProcInfoToHost.getProcPath() + "/proc/" + pidFile + "/cmdline")," ");
                                String jmxPort = getJMXPort(cmd);
                                String sign = jmxPort == null ? "-NonJmxPort" : "-JP_" + jmxPort;
                                if ("*".equals(serverName)){
                                    javaExecCommandInfos.add(new JavaExecCommandInfo(appName + sign,containerIp,cmd));
                                }else if (hasContainsServerNameForContainer(cmd,serverName)){
                                    javaExecCommandInfos.add(new JavaExecCommandInfo(appName + sign,containerIp,cmd));
                                }
                            }
                        }
                    }
                }else {
                    log.warn("未找到容器{}的appName",containerProcInfoToHost.getContainerId());
                }
            }
        }
        CacheByTimeUtil.setCache(cacheKey,javaExecCommandInfos);
        return javaExecCommandInfos;
    }

    /**
     * 获取/tmp目录下所有的Java进程的pid
     * @return
     */
    public static List<String> getPidListFromTmp(){
        return getPidListFromTmp("/tmp");
    }

    /**
     * 获取{tmpDir}目录下所有的Java进程的pid
     * @param tmpDir
     * @return
     */
    public static List<String> getPidListFromTmp(String tmpDir){
        String cacheKey = "pidListFromTmpDir" + tmpDir;
        List<String> pidList = (List<String>) CacheByTimeUtil.getCache(cacheKey);
        if (pidList != null){
            return pidList;
        }
        pidList = new ArrayList<>();
        if (StringUtils.isNotEmpty(tmpDir)){
            File file_tmpDir = new File(tmpDir);
            if (file_tmpDir.exists()){
                String[] hsperfDataDirs = file_tmpDir.list((dir, name) -> name.startsWith("hsperfdata"));
                if (hsperfDataDirs != null) {
                    List<File> files_hsperfData = new ArrayList<>();
                    for (String hsperfDataDir : hsperfDataDirs) {
                        files_hsperfData.add(new File(tmpDir + "/" + hsperfDataDir));
                    }
                    for (File file_hsperfDatum : files_hsperfData) {
                        if (!file_hsperfDatum.canRead()){
                            log.error("没有目录的读取权限：{}",file_hsperfDatum.getAbsolutePath());
                            continue;
                        }
                        String[] pidFiles = file_hsperfDatum.list();
                        if (pidFiles != null) {
                            Collections.addAll(pidList, pidFiles);
                        }
                    }
                }
            }else {
                log.error("目录{}不存在或无访问权限",tmpDir);
            }
        }
        CacheByTimeUtil.setCache(cacheKey,pidList);
        return pidList;
    }

    /**
     * 获取指定服务名的本地JMX VM 描述对象
     * @param serverName
     * @return
     */
    public static List<VirtualMachineDescriptor> getVmDescByServerName(String serverName){
        List<VirtualMachineDescriptor> vmDescList = new ArrayList<>();
        if (StringUtils.isEmpty(serverName)){
            return vmDescList;
        }
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        for (VirtualMachineDescriptor desc : vms) {
            //java -jar 形式启动的Java应用
            if(desc.displayName().matches(".*\\.jar(\\s*-*.*)*") && desc.displayName().contains(serverName)){
                vmDescList.add(desc);
            }else if(hasContainsServerName(desc.displayName(),serverName)){
                vmDescList.add(desc);
            }else if (isJSVC(desc.id(),serverName)){
                VirtualMachineDescriptor descriptor = new VirtualMachineDescriptor(desc.provider(),desc.id(),serverName);
                vmDescList.add(descriptor);
            }
        }
        return vmDescList;
    }

    /**
     * 是否为jsvc方式启动的java应用
     * @param pid
     * @param serverName
     * @return
     */
    private static boolean isJSVC(String pid,String serverName){
        if (StringUtils.isEmpty(serverName) || AgentConfiguration.INSTANCE.isDockerRuntime()){
            return false;
        }
        String name = serverName;
        if (serverName.equals("org.apache.catalina.startup.Bootstrap start")){
            name = "org.apache.catalina.startup.Bootstrap";
        }
        String cmd = String.format("ps u -p %s | grep jsvc",pid);
        try {
            CommandUtilForUnix.ExecuteResult executeResult = CommandUtilForUnix.execWithReadTimeLimit(cmd,false,7);
            return executeResult.msg.contains(name);
        } catch (IOException e) {
            log.error("",e);
            return false;
        }
    }

    /**
     * 判断指定的目标服务名是否在探测的CMD中
     * @param cmd
     * @param serverName
     * @return
     */
    public static boolean hasContainsServerNameForContainer(String cmd,String serverName){
        if (StringUtils.isEmpty(serverName)){
            return false;
        }
        boolean has = true;
        List<String> displaySplit = Arrays.asList(cmd.split("\\s+"));
        for (String s : serverName.split("\\s+")) {
            if (displaySplit.stream().filter(str -> str.contains(s)).count() == 0){
                has = false;
                break;
            }
        }
        return has;
    }

    /**
     * 判断指定的目标服务名是否在探测的展示名中
     * @param displayName
     * @param serverName
     * @return
     */
    public static boolean hasContainsServerName(String displayName,String serverName){
        if (StringUtils.isEmpty(serverName)){
            return false;
        }
        boolean has = true;
        List<String> displaySplit = Arrays.asList(displayName.split("\\s+"));
        for (String s : serverName.split("\\s+")) {
            //boot  start
            if (!displaySplit.contains(s)){
                has = false;
                break;
            }
        }
        return has;
    }

    /**
     * 获取JMX Remote端口号
     * @param pid
     * @return
     */
    public static String getJMXPort(int pid){
        String cmdForMac = "ps u -p " + pid;
        String cmdForLinux = "cat /proc/" + pid + "/cmdline";
        try {
            CommandUtilForUnix.ExecuteResult result;
            if (OSUtil.isLinux()){
                result = CommandUtilForUnix.execWithReadTimeLimit(cmdForLinux,false,7);
            }else if (OSUtil.isMac()){
                result = CommandUtilForUnix.execWithReadTimeLimit(cmdForMac,false,7);
            }else {
                log.error("只支持Linux和Mac平台");
                return null;
            }

            return getJMXPort(result.msg);
        } catch (IOException e) {
            log.error("",e);
            return null;
        }
    }

    /**
     * 获取JMX Remote端口号
     * @param cmd
     * @return
     */
    public static String getJMXPort(String cmd){
        String jmxPortOpt = "-Dcom.sun.management.jmxremote.port";
        String port = null;
        if (OSUtil.isLinux()){
            port = getJMXConfigValueForLinux(cmd,jmxPortOpt + "=\\d+",jmxPortOpt + "=");
        }else if (OSUtil.isMac()){
            port = getJMXConfigValueForMac(cmd,jmxPortOpt);
        }
        if (port == null){
            log.warn("未找到JMX端口号");
        }
        return port;
    }

}

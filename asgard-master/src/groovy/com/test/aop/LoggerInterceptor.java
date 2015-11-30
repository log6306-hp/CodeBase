package com.test.aop;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

import javax.servlet.http.HttpServletRequestWrapper;

import org.apache.commons.validator.Field;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.asgard.UserContext;

@Aspect
public class LoggerInterceptor {

	private static Logger logger = LoggerFactory
			.getLogger(LoggerInterceptor.class);
	
	static File fileLog = new File("D:\\logAsgard.txt");
	static PrintWriter pw;
	static{
		try{
			pw = new PrintWriter(fileLog);
		}catch(Exception ex){
			ex.printStackTrace();
		}
	}
	
	@Before("within(com.netflix.asgard.Aws*)")
	public void logBefore(JoinPoint joinPoint) {
		
		try {
			
			if(pw == null){
				pw = new PrintWriter(fileLog);
			}
			
			String time  = Calendar.getInstance().getTime().toString();
			
			System.out.println(time + " Class:"+ joinPoint.getTarget().getClass().getName() + 
					", Method:"+joinPoint.getSignature().getName());
			
			String logMessage = String.format(time + 
					" , Beginning of each method: %s.%s(%s)", joinPoint
							.getTarget().getClass().getName(), joinPoint
							.getSignature().getName(), Arrays
							.toString(joinPoint.getArgs()));
			//logger.info(logMessage);
			pw.println(logMessage);
			pw.flush();
		} catch (Exception e) {
			System.err.println("error ...");
			pw.close();
		}
	}
}
package org.broadleafcommerce.common.security.handler;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.security.web.util.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

public class CsrfSecurityRequestMatcher implements RequestMatcher {
    
    protected List<String> excludedRequestPatterns;

 
    @Override
    public boolean matches(HttpServletRequest request) {
        boolean excludedRequestFound = false;
        if (excludedRequestPatterns != null && excludedRequestPatterns.size() > 0) {
            for (String pattern : excludedRequestPatterns) {
                RequestMatcher matcher = new AntPathRequestMatcher(pattern);
                if (matcher.matches(request)){
                    excludedRequestFound = true;
                    break;
                }
            }
        } 
        if (request.getMethod().equals("POST") && !excludedRequestFound) { 
            return true;
        }
        return false;
    }
    
    public List<String> getExcludedRequestPatterns() {
        return excludedRequestPatterns;
    }

    /**
     * This allows you to declaratively set a list of excluded Request Patterns
     *
     * <bean id="blCsrfFilter" class="org.broadleafcommerce.common.security.handler.CsrfFilter" >
     *     <property name="excludedRequestPatterns">
     *         <list>
     *             <value>/exclude-me/**</value>
     *         </list>
     *     </property>
     * </bean>
     *
     **/
    public void setExcludedRequestPatterns(List<String> excludedRequestPatterns) {
        this.excludedRequestPatterns = excludedRequestPatterns;
    }
}
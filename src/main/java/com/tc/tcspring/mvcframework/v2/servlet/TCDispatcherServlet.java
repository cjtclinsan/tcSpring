package com.tc.tcspring.mvcframework.v2;

import com.tc.tcspring.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TCDispatcherServlet extends HttpServlet {
    /**保存application.properties配置文件中的内容*/
    private Properties contextConfig = new Properties();
    /**保存所有扫描到的类名*/
    private List<String> classNames = new ArrayList<>();
    /**IOC容器，为了简化，暂不考虑ConcurrentHashMap*/
    private Map<String, Object> ioc = new HashMap<>();
    /**保存url和method的关系
     * 为什么不用map？
     * 用map的话，key只能是url
     * Handler本身的功能就是把url和method对应关系，已经具备mao的功能
     * */
    private List<Handler> handlerMapping = new ArrayList<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exection,Detail : " + Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception{
        Handler handler = getHandler(req);
        if( handler == null ){
            resp.getWriter().write("404 Not Found!!!");
            return;
        }

        //获得方法的形参列表
        Class<?> [] paramTypes = handler.getParamTypes();

        Object[] paramValues = new Object[paramTypes.length];

        Map<String, String[]> params = req.getParameterMap();
        for (Map.Entry<String, String[]> param : params.entrySet()) {
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]","")
                    .replaceAll("\\s",",");

            if( !handler.paramIndexMapping.containsKey(param.getKey()) ){
                continue;
            }

            int index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(paramTypes[index], value);
        }

        if( handler.paramIndexMapping.containsKey(HttpServletRequest.class) ){
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class);
            paramValues[reqIndex] = req;
        }

        if( handler.paramIndexMapping.containsKey(HttpServletResponse.class) ){
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class);
            paramValues[respIndex] = resp;
        }

        Object returnValue = handler.method.invoke(handler.controller, paramValues);
        if( returnValue == null || returnValue instanceof Void ){
            return;
        }

        resp.getWriter().write(returnValue.toString());
    }

    private Handler getHandler(HttpServletRequest req) {
        if( handlerMapping.isEmpty() ){
            return null;
        }
        //绝对路径
        String url = req.getRequestURI();
        //处理成相对路径
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");

        for (Handler handler : this.handlerMapping) {
            Matcher matcher = handler.getPattern().matcher(url);
            if( !matcher.matches() ){
                continue;
            }
            return handler;
        }
        return null;
    }

    //初始化阶段
    @Override
    public void init(ServletConfig config) throws ServletException {
        //1，加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        //2，扫描相关的类
        doScanner(contextConfig.getProperty("sacnPackage"));

        //3，初始化扫描到的类，放入IOC容器中
        doInstance();

        //4，完成依赖注入
        doAutowired();

        //5，初始化handlerMapping
        initHandlerMapping();

        System.out.println("TC Spring framework is init.");
    }

    /**初始化url和method的一对一关系*/
    private void initHandlerMapping() {
        if( ioc.isEmpty() ){
            return;
        }

        for ( Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();

            if( !clazz.isAnnotationPresent(TCController.class) ){
                continue;
            }

            //保存写在类上面的@GPRequestMapping("/demo")
            String baseUrl = "";
            if( clazz.isAnnotationPresent(TCRequestMapping.class) ){
                TCRequestMapping requestMapping = clazz.getAnnotation(TCRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            //默认获取所有的public方法
            for ( Method method : clazz.getMethods() ) {
                if( !method.isAnnotationPresent(TCRequestMapping.class) ){
                    continue;
                }

                TCRequestMapping requestMapping = method.getAnnotation(TCRequestMapping.class);
                //优化，正则
                String regex = ("/"+baseUrl+"/"+requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);
                this.handlerMapping.add(new Handler(pattern, entry.getValue(), method));

                System.out.println("Mapped:"+pattern+","+method);
            }
        }
    }

    /**依赖注入*/
    private void doAutowired() {
        if ( ioc.isEmpty() ) {
            return;
        }

        for ( Map.Entry<String, Object> entry : ioc.entrySet() ) {
            //Declared 所有的，包括private/protected/default
            //正常来说，普通的oop编程只能拿到public的属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for ( Field field : fields ) {
                if( !field.isAnnotationPresent(TCAutowired.class) ){
                    continue;
                }

                TCAutowired autowired = field.getAnnotation(TCAutowired.class);

                //如果用户没有自定义beanName，默认根据类型注入
                //省去了大小写的判断
                String beanName = autowired.value().trim();
                if( "".equals(beanName) ){
                    //获得接口类型，作为key，待会拿这个key到ioc容器中去取值
                    beanName = field.getType().getName();
                }

                //如果是public以外的修饰符，只要加Autowired注解，都需要强制赋值
                field.setAccessible(true);

                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doInstance() {
        //初始化，为DI做准备
        if( classNames.isEmpty() ){
            return;
        }

        try {
            for ( String className : classNames) {
                Class<?> clazz = Class.forName(className);

                //加了注解的类需要初始化，为了简化代码逻辑，只举例 @Controller @Service
                if( clazz.isAnnotationPresent(TCController.class) ){
                    Object instance = clazz.newInstance();
                    //Spring默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                }else if( clazz.isAnnotationPresent(TCService.class) ){
                    //1,自定义的beanName
                    TCService service = clazz.getAnnotation(TCService.class);
                    String beanName = service.value();
                    //2，默认类名首字母小写
                    if( "".equals(beanName.trim()) ){
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }

                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);

                    //3，根据类型自动赋值
                    for ( Class<?> i : clazz.getInterfaces() ) {
                        if( ioc.containsKey(i.getName()) ){
                            throw new Exception("The “"+i.getName()+"” is exist!");
                        }
                        //把接口类型直接当做key
                        ioc.put(i.getName(), instance);
                    }
                }else {
                    continue;
                }
            }
        } catch ( Exception e ){
            e.printStackTrace();
        }
    }

    /**扫描出相关的类*/
    private void doScanner(String scanPackage) {
        //scanPackage=com.tc.demo,存储的是包路径
        //转换为文件路径，实际上就是把.替换为/就OK了
        URL url = this.getClass().getClassLoader().getResource("/"+scanPackage.replaceAll("\\.", "/"));
        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            if( file.isDirectory() ){
                doScanner(scanPackage+"."+file.getName());
            }else {
                if( !file.getName().endsWith(".class") ){
                    continue;
                }
                String className = (scanPackage+"."+file.getName().replace(".class",""));
                classNames.add(className);
            }
        }
    }

    /**加载配置文件*/
    private void doLoadConfig(String contextConfigLocation) {
        //直接从类路径下找到spring主配置文件所在的路径
        //并且将其读取出来放到Properties对象中
        //相当于scanPackage=com.tc.demo从文件中保存到内存中
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if( fis != null ){
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 保存url和method的关系
     */
    private class Handler {
        private Pattern pattern;
        private Object controller;
        private Method method;

        private Class<?> [] paramTypes;

        public Pattern getPattern() {
            return pattern;
        }

        public void setPattern(Pattern pattern) {
            this.pattern = pattern;
        }

        public Method getMethod() {
            return method;
        }

        public void setMethod(Method method) {
            this.method = method;
        }

        public Object getController() {
            return controller;
        }

        public void setController(Object controller) {
            this.controller = controller;
        }

        public Class<?>[] getParamTypes() {
            return paramTypes;
        }

        public void setParamTypes(Class<?>[] paramTypes) {
            this.paramTypes = paramTypes;
        }

        /**形参列表，参数名为key，参数顺序为value*/
        private Map<String, Integer> paramIndexMapping;

        public Handler(Pattern pattern, Object controller, Method method) {
            this.pattern = pattern;
            this.method = method;
            this.controller = controller;

            paramTypes = method.getParameterTypes();
            
            paramIndexMapping = new HashMap<>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method) {

        }
    }

    /**如果类名本身是小写，会出问题，但在标准的情况下是可以的*/
    private String toLowerFirstCase(String simpleName){
        char[] chars = simpleName.toCharArray();

        // 因为大小写字母的ASCII码相差32，
        // 而且大写字母的ASCII码要小于小写字母的ASCII码
        // 在Java中，对char做算学运算，实际上就是对ASCII码做算学运算
        chars[0] += 32;
        return String.valueOf(chars);
    }

    /**
     * url传过来的参数是String类型的，Http是基于字符串协议的
     * 只需要把String转换成任意类型就好
     */
    private Object convert(Class<?> type, String value){
        if( Integer.class == type ){
            return Integer.valueOf(value);
        }else if( Double.class == type ){
            return Double.valueOf(value);
        }
        //策略模式
        return value;
    }
}
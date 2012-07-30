package com.laytonsmith.persistance;

import com.laytonsmith.PureUtilities.FileUtility;
import com.laytonsmith.PureUtilities.StringUtils;
import com.laytonsmith.core.CHLog;
import com.laytonsmith.core.constructs.Target;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Given a File, creates a data source filter, which can be 
 * @author layton
 */
public class DataSourceFilter {
    
    private Map<Pattern, String> mappings = new HashMap<Pattern, String>();
    private Map<Pattern, String> multimap = new HashMap<Pattern, String>();
    private Map<Pattern, String> original = new HashMap<Pattern, String>();
    private static Pattern captureUsage = Pattern.compile("\\$(\\d+)");
    //Since data lookups are expensive, cache them.
    private Map<String, URI> cache = new TreeMap<String, URI>();
    
    /**
     * Creates a new data source filter. This is represented by a file that contains mappings to the filters.
     * @param file The file that contains the filter network
     * @param defaultURI The URI to be used in the case that no ** filter is found in the file
     * @throws FileNotFoundException If the file cannot be found
     * @throws DataSourceException If any condition causes the data sources to be unreadable
     * @throws NullPointerException If the defaultURI is null
     */
    public DataSourceFilter(File file, URI defaultURI) throws FileNotFoundException, DataSourceException{
        try{
            process(FileUtility.read(file), defaultURI);
        } catch(DataSourceException e){
            throw new DataSourceException("Could not process filter file located at " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }
    
    /**
     * Creates a new data source filter. This is represented by a string that contains mappings to the filters.
     * @param filters The filter configuration that contains the filter network
     * @param defaultURI The URI to be used in the case that no ** filter is found in the file
     * @throws DataSourceException If any condition causes the data sources to be unreadable
     * @throws NullPointerException If the defaultURI is null
     */
    public DataSourceFilter(String filters, URI defaultURI) throws DataSourceException{
        process(filters, defaultURI);
    }
    
    private void process(String filters, URI defaultURI) throws DataSourceException{
        if(defaultURI == null){
            throw new NullPointerException("defaultURI cannot be null");
        }
        
        //TODO: Finish with defaultURI
        Properties p = new Properties();
        try {
            StringReader sr = new StringReader(filters);
            p.load(sr);
        } catch (IOException ex) {
            //Won't happen.
        }
        //We now have the whole list of filters=connection. We need to parse out and
        //find the connection aliases and normal filters, then parse those individually.
        //First, find the aliases, so we can go ahead and distribute those out when
        //we get to them.
        Map<String, String> aliases = new HashMap<String, String>();
        for(String key : p.stringPropertyNames()){
            if(key.matches("\\$[^a-zA-Z_]+")){
                //Bad alias, bail                
                throw new DataSourceException("Aliases in your filters may not start with a digit.");
            }
            if(key.matches("\\$[a-zA-Z_][a-zA-Z0-9_]*")){
                //It's an alias
                aliases.put(key, p.getProperty(key));
            }
        }
        
        boolean hasDefault = false;
        
        //Ok, now let's load up the actual connections.
        for(String key : p.stringPropertyNames()){
            if(!key.matches("\\$.*")){
                if(key.equals("**")){
                    
                }
                if(key.matches("[^a-zA-Z0-9_\\(\\)\\*]")){
                    //Bad character in the filter. Bail.
                    throw new DataSourceException("Invalid character in filter. Only"
                            + " the following characters are allowed: a-zA-Z0-9_()*"
                            + " Found this instead: " + key);
                }
                //We need to change * into [^\.]*? and ** into .*? and . into \.
                //Parenthesis are kept as is.
                String newKey = key.replaceAll("\\.", "\\\\.");
                StringBuilder b = new StringBuilder("^");
                for(int i = 0; i < newKey.length(); i++){
                    Character c1 = newKey.charAt(i);
                    Character c2 = null;
                    if(i + 1 < newKey.length()){
                        c2 = newKey.charAt(i + 1);
                    }
                    if(c1 == '*' && c2 != null && c2 == '*'){
                        //Double star
                        b.append(".*?");
                        i++;
                    } else if(c1 == '*'){
                        //Single star
                        b.append("[^\\.]*?");
                    } else {
                        //Some other character
                        b.append(c1);
                    }                    
                }
                String regexKey = b.toString();
                
                //This is the number of expected capture usages to be found in the
                //associated value
                int expected = 0;
                
                //Now, we need to look to make sure that parenthesis are correct.
                boolean inParenthesis = false;
                for(Character c : regexKey.toCharArray()){
                    if(c.equals('(')){
                        if(inParenthesis){
                            //Nope.
                            throw new DataSourceException("Invalid filter, new"
                                    + " capture started before closing the previous one (do"
                                    + " you have two left parenthesis in a row?) in: " + key);
                        }
                        expected++;
                        inParenthesis = true;
                    }
                    if(c.equals(')')){
                        if(!inParenthesis){
                            throw new DataSourceException("Invalid filter, capture"
                                    + " group ended, but one had not been started (do you have"
                                    + " an extra right parenthesis without a matching left?) in: " + key);
                        }
                        inParenthesis = false;
                    }
                }
                if(inParenthesis){
                    throw new DataSourceException("Invalid filter, capture group not ended (did you"
                            + " forget to close a left parenthesis?) in: " + key);
                }
                Pattern pattern = Pattern.compile(regexKey + "$");
                Pattern multipattern = Pattern.compile(regexKey + ".*");
                
                //Ok, have the pattern, now lets see if the value is an alias
                
                String value = p.getProperty(key);
                String originalValue = value;
                //Used for more meaningful error messages below
                boolean isAlias = false;
                if(value.matches("\\$.*")){
                    if(!aliases.containsKey(value)){
                        throw new DataSourceException("Invalid alias: " + value + " is trying to be"
                                + " used, but has not been defined.");
                    } else {
                        value = aliases.get(value);
                        isAlias = true;
                    }                    
                }
                //Now, let's check to make sure that any captures are valid
                Matcher m = captureUsage.matcher(value);
                while(m.find()){
                    int i = Integer.parseInt(m.group(1));
                    if(i < 1 || i > expected){
                        //Show a very detailed error message.
                        throw new DataSourceException("Invalid capture group \"$" + i + "\". " +
                                (expected == 0?"No capture usages were expected":"Only"
                                + " " + expected + " capture" + (expected!=1?"s":"") + " were"
                                + " expected") + " for the connection: " + value 
                                + (isAlias?"(Defined as alias " + originalValue + ")":""));
                    }
                }
                
                //Is this pattern already in the mapping? If so, we need to throw an error.
                if(mappings.containsKey(pattern)){
                    throw new DataSourceException("Multiple definitions exist for the key: " + key);
                }
                
                //Ok, finally, one last validation, we want to make sure that the URI at least
                //looks valid enough. Dollar signs aren't valid though, so lets replace our otherwise
                //valid capture usages.
                try{
                    new URI(value.replaceAll("\\$\\d*", "_"));
                } catch(URISyntaxException e){
                    throw new DataSourceException("Invalid URI for " + value 
                            + (isAlias?"(Defined for alias " + originalValue + ")":"") + ".");
                }                
                //Alright. It's cool. Add it to the list.
                mappings.put(pattern, value);
                multimap.put(multipattern, value);
                original.put(pattern, key);
            }
            //else it's an alias, and we've already dealt with it
        }
    }
    
    /**
     * Given a full key, returns the connection that contains it.
     * @param key
     * @return 
     */
    public URI getConnection(String [] key){
        return getConnection(StringUtils.Join(key, "."));
    }
    
    /**
     * Returns all the connections that actually match this key.
     * @param key
     * @return 
     */
    public List<URI> getAllConnections(String [] key){
        return getAllConnections(StringUtils.Join(key, "."));
    }
    
    /**
     * Returns all the connections that actually match this key. This is typically
     * used to get a subset of keys based on namespace.
     * @param key
     * @return 
     */
    public List<URI> getAllConnections(String key){
        List<URI> matches = new ArrayList<URI>();
        for(Pattern p : multimap.keySet()){
            if(p.matcher(key).matches()){
                try {
                    matches.add(new URI(multimap.get(p)));
                } catch (URISyntaxException ex) {
                    //won't happen
                }
            }
        }
        return matches;
    }
    
    /**
     * Given a full key, returns the connection that contains it.
     * @param key
     * @return 
     */
    public URI getConnection(String key){
        //Since looking through these patterns, doing the matches, calculating string distance are all
        //fairly expensive operations, let's improve the runtime complexity by using a cache
        if(cache.containsKey(key)){
                return cache.get(key);
        }
        List<Pattern> matches = new ArrayList<Pattern>();
        for(Pattern p : mappings.keySet()){
            if(p.matcher(key).matches()){
                matches.add(p);
            }
        }
        //Ok, we have a list of the actual matches, we have to narrow it down to the closest
        //match.
        Pattern closest = null;
        if(matches.isEmpty()){
            //Trivial case
            return null;
        } else if(matches.size() == 1){
            //Yay! Also a trivial case!            
            closest = matches.get(0);
        } else {
            int lowest = Integer.MAX_VALUE;
            for(Pattern p : matches){
                //The closest match is defined as a filter that, minus wild cards, matches more characters.
                //So, for instance, if the key is a.b.c.d, then this matches a.*.c.d better than a.*.*.d
                //The easiest way to detect this is to simply remove *() characters, and do a Levenshtein distance on the strings, and
                //whichever one is lowest, is the closest.
                String originalKey = original.get(p);
                int dist = StringUtils.LevenshteinDistance(key, originalKey.replaceAll("\\*", "").replaceAll("[\\(\\)]", ""));
                //TODO: Unfortunately, the properties file doesn't keep crap in order for us, so right now,
                //if there is a tie, it is undefined what will happen. Instead of letting this happen
                //without a warning, we want to issue a warning, though we will arbitrarily select one.
                if(dist == lowest){
                    CHLog.Log(CHLog.Tags.PERSISTANCE, "Two keys equally match for the key \"" + key 
                            + "\". Both " + original.get(closest) + " and " + original.get(p) 
                            + " match just as well. For the time being, this is an undefined result, but"
                            + " for this time, " + original.get(closest) + " is being selected.", Target.UNKNOWN);
                }
                if(dist < lowest){
                    closest = p;
                    lowest = dist;
                }
            }
        }
        
        try {
            if(closest == null){
                return null;
            }
            String uri = mappings.get(closest);
            Matcher m = closest.matcher(key);
            while(m.find()){
                //We need to replace the captures
                for(int i = 1; i <= m.groupCount(); i++){
                    uri = uri.replaceAll("\\$" + i, m.group(i));
                }
            }
            URI u = new URI(uri);
            //Store it in our cache
            cache.put(key, u);
            return u;
        } catch (URISyntaxException ex) {
            //We already verified that this won't happen, so yeah.
            return null;
        }        
    }
}

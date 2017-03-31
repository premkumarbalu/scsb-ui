package org.recap.controller;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.marc4j.marc.Record;
import org.recap.RecapConstants;
import org.recap.model.jpa.*;
import org.recap.model.request.CancelRequestResponse;
import org.recap.model.request.ItemRequestInformation;
import org.recap.model.request.ItemResponseInformation;
import org.recap.model.search.RequestForm;
import org.recap.model.search.SearchResultRow;
import org.recap.model.usermanagement.UserDetailsForm;
import org.recap.repository.jpa.*;
import org.recap.security.UserManagementService;
import org.recap.util.BibJSONUtil;
import org.recap.util.RequestServiceUtil;
import org.recap.util.UserAuthUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.support.BindingAwareModelMap;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.text.NumberFormat;
import java.util.*;

/**
 * Created by rajeshbabuk on 13/10/16.
 */

@Controller
public class RequestController {

    private static final Logger logger = LoggerFactory.getLogger(RequestController.class);

    @Value("${server.protocol}")
    String serverProtocol;

    @Value("${scsb.url}")
    String scsbUrl;

    @Value("${scsb.shiro}")
    String scsbShiro;

    @Autowired
    RequestServiceUtil requestServiceUtil;

    @Autowired
    InstitutionDetailsRepository institutionDetailsRepository;

    @Autowired
    RequestTypeDetailsRepository requestTypeDetailsRepository;

    @Autowired
    CustomerCodeDetailsRepository customerCodeDetailsRepository;

    @Autowired
    ItemDetailsRepository itemDetailsRepository;

    @Autowired
    RequestStatusDetailsRepository requestStatusDetailsRepository;

    @Autowired
    RequestItemDetailsRepository requestItemDetailsRepository;

    @Autowired
    private UserAuthUtil userAuthUtil;

    public RequestServiceUtil getRequestServiceUtil() {
        return requestServiceUtil;
    }

    public void setRequestServiceUtil(RequestServiceUtil requestServiceUtil) {
        this.requestServiceUtil = requestServiceUtil;
    }

    public UserAuthUtil getUserAuthUtil() {
        return userAuthUtil;
    }

    public void setUserAuthUtil(UserAuthUtil userAuthUtil) {
        this.userAuthUtil = userAuthUtil;
    }

    public InstitutionDetailsRepository getInstitutionDetailsRepository() {
        return institutionDetailsRepository;
    }

    public void setInstitutionDetailsRepository(InstitutionDetailsRepository institutionDetailsRepository) {
        this.institutionDetailsRepository = institutionDetailsRepository;
    }

    public RequestTypeDetailsRepository getRequestTypeDetailsRepository() {
        return requestTypeDetailsRepository;
    }

    public void setRequestTypeDetailsRepository(RequestTypeDetailsRepository requestTypeDetailsRepository) {
        this.requestTypeDetailsRepository = requestTypeDetailsRepository;
    }

    public CustomerCodeDetailsRepository getCustomerCodeDetailsRepository() {
        return customerCodeDetailsRepository;
    }

    public void setCustomerCodeDetailsRepository(CustomerCodeDetailsRepository customerCodeDetailsRepository) {
        this.customerCodeDetailsRepository = customerCodeDetailsRepository;
    }

    public ItemDetailsRepository getItemDetailsRepository() {
        return itemDetailsRepository;
    }

    public void setItemDetailsRepository(ItemDetailsRepository itemDetailsRepository) {
        this.itemDetailsRepository = itemDetailsRepository;
    }

    public String getServerProtocol() {
        return serverProtocol;
    }

    public String getScsbShiro() {
        return scsbShiro;
    }

    public String getScsbUrl() {
        return scsbUrl;
    }

    public RequestStatusDetailsRepository getRequestStatusDetailsRepository() {
        return requestStatusDetailsRepository;
    }

    public RequestItemDetailsRepository getRequestItemDetailsRepository() {
        return requestItemDetailsRepository;
    }

    public RestTemplate getRestTemplate() {
        return new RestTemplate();
    }

    @RequestMapping("/request")
    public String request(Model model, HttpServletRequest request) throws JSONException {
        HttpSession session = request.getSession();
        boolean authenticated = getUserAuthUtil().authorizedUser(RecapConstants.SCSB_SHIRO_REQUEST_URL, (UsernamePasswordToken) session.getAttribute(RecapConstants.USER_TOKEN));
        if (authenticated) {
            UserDetailsForm userDetailsForm = getUserAuthUtil().getUserDetails(session, RecapConstants.REQUEST_PRIVILEGE);
            RequestForm requestForm = setDefaultsToCreateRequest(userDetailsForm);
            Object requestedBarcode = ((BindingAwareModelMap) model).get(RecapConstants.REQUESTED_BARCODE);
            if (requestedBarcode != null) {
                requestForm.setItemBarcodeInRequest((String) requestedBarcode);
                String stringJson = populateItem(requestForm, null, model, request);
                if (stringJson != null) {
                    JSONObject jsonObject = new JSONObject(stringJson);
                    Object itemTitle = jsonObject.has(RecapConstants.REQUESTED_ITEM_TITLE) ? jsonObject.get(RecapConstants.REQUESTED_ITEM_TITLE) : null;
                    Object itemOwningInstitution = jsonObject.has(RecapConstants.REQUESTED_ITEM_OWNING_INSTITUTION) ? jsonObject.get(RecapConstants.REQUESTED_ITEM_OWNING_INSTITUTION) : null;
                    Object deliveryLocations = jsonObject.has(RecapConstants.DELIVERY_LOCATION) ? jsonObject.get(RecapConstants.DELIVERY_LOCATION) : null;
                    List<CustomerCodeEntity> customerCodeEntities = new ArrayList<>();
                    if (itemTitle != null && itemOwningInstitution != null && deliveryLocations != null) {
                        requestForm.setItemTitle((String) itemTitle);
                        requestForm.setItemOwningInstitution((String) itemOwningInstitution);
                        JSONObject deliveryLocationsJson = (JSONObject) deliveryLocations;
                        Iterator iterator = deliveryLocationsJson.keys();
                        while (iterator.hasNext()) {
                            String customerCode = (String) iterator.next();
                            String description = (String) deliveryLocationsJson.get(customerCode);
                            CustomerCodeEntity customerCodeEntity = new CustomerCodeEntity();
                            customerCodeEntity.setCustomerCode(customerCode);
                            customerCodeEntity.setDescription(description);
                            customerCodeEntities.add(customerCodeEntity);
                        }
                        requestForm.setDeliveryLocations(customerCodeEntities);
                    }
                }
            }
            model.addAttribute(RecapConstants.REQUEST_FORM, requestForm);
            model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
            return RecapConstants.VIEW_SEARCH_RECORDS;
        } else {
            return UserManagementService.unAuthorizedUser(session, "Request", logger);
        }
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=searchRequests")
    public ModelAndView searchRequests(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                       BindingResult result,
                                       Model model) {
        try {
            requestForm.resetPageNumber();
            searchAndSetResults(requestForm);
            model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        } catch (Exception exception) {
            logger.error(RecapConstants.LOG_ERROR, exception);
            logger.debug(exception.getMessage());
        }
        return new ModelAndView(RecapConstants.VIEW_SEARCH_REQUESTS_SECTION, RecapConstants.REQUEST_FORM, requestForm);
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=first")
    public ModelAndView searchFirst(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                    BindingResult result,
                                    Model model) {
        requestForm.resetPageNumber();
        searchAndSetResults(requestForm);
        model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        return new ModelAndView(RecapConstants.VIEW_SEARCH_REQUESTS_SECTION, RecapConstants.REQUEST_FORM, requestForm);
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=last")
    public ModelAndView searchLast(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                   BindingResult result,
                                   Model model) {
        requestForm.setPageNumber(requestForm.getTotalPageCount() - 1);
        searchAndSetResults(requestForm);
        model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        return new ModelAndView(RecapConstants.VIEW_SEARCH_REQUESTS_SECTION, RecapConstants.REQUEST_FORM, requestForm);
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=previous")
    public ModelAndView searchPrevious(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                       BindingResult result,
                                       Model model) {
        searchAndSetResults(requestForm);
        model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        return new ModelAndView(RecapConstants.VIEW_SEARCH_REQUESTS_SECTION, RecapConstants.REQUEST_FORM, requestForm);
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=next")
    public ModelAndView searchNext(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                   BindingResult result,
                                   Model model) {
        searchAndSetResults(requestForm);
        model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        return new ModelAndView(RecapConstants.VIEW_SEARCH_REQUESTS_SECTION, RecapConstants.REQUEST_FORM, requestForm);
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=requestPageSizeChange")
    public ModelAndView onRequestPageSizeChange(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                                BindingResult result,
                                                Model model) {
        requestForm.setPageNumber(getPageNumberOnPageSizeChange(requestForm));
        searchAndSetResults(requestForm);
        model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        return new ModelAndView(RecapConstants.VIEW_SEARCH_REQUESTS_SECTION, RecapConstants.REQUEST_FORM, requestForm);
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=loadCreateRequest")
    public ModelAndView loadCreateRequest(Model model, HttpServletRequest request) {
        UserDetailsForm userDetailsForm = getUserAuthUtil().getUserDetails(request.getSession(), RecapConstants.REQUEST_PRIVILEGE);
        RequestForm requestForm = setDefaultsToCreateRequest(userDetailsForm);
        model.addAttribute(RecapConstants.REQUEST_FORM, requestForm);
        model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        return new ModelAndView(RecapConstants.REQUEST, RecapConstants.REQUEST_FORM, requestForm);
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=loadSearchRequest")
    public ModelAndView loadSearchRequest(Model model, HttpServletRequest request) {
        RequestForm requestForm = new RequestForm();
        List<String> requestStatuses = new ArrayList<>();
        Iterable<RequestStatusEntity> requestStatusEntities = requestStatusDetailsRepository.findAll();
        for (Iterator iterator = requestStatusEntities.iterator(); iterator.hasNext(); ) {
            RequestStatusEntity requestStatusEntity = (RequestStatusEntity) iterator.next();
            requestStatuses.add(requestStatusEntity.getRequestStatusDescription());
        }
        requestForm.setRequestStatuses(requestStatuses);
        model.addAttribute(RecapConstants.REQUEST_FORM, requestForm);
        model.addAttribute(RecapConstants.TEMPLATE, RecapConstants.REQUEST);
        return new ModelAndView(RecapConstants.REQUEST, RecapConstants.REQUEST_FORM, requestForm);
    }

    private RequestForm setDefaultsToCreateRequest(UserDetailsForm userDetailsForm) {
        RequestForm requestForm = new RequestForm();

        List<String> requestingInstitutions = new ArrayList<>();
        List<String> requestTypes = new ArrayList<>();
        List<CustomerCodeEntity> deliveryLocations = new ArrayList<>();

        Iterable<InstitutionEntity> institutionEntities = getInstitutionDetailsRepository().findAll();
        for (Iterator iterator = institutionEntities.iterator(); iterator.hasNext(); ) {
            InstitutionEntity institutionEntity = (InstitutionEntity) iterator.next();
            if (userDetailsForm.getLoginInstitutionId() == institutionEntity.getInstitutionId()) {
                requestForm.setRequestingInstitution(institutionEntity.getInstitutionCode());
            }
            if ((userDetailsForm.getLoginInstitutionId() == institutionEntity.getInstitutionId() || userDetailsForm.isRecapUser() || userDetailsForm.isSuperAdmin()) && (!RecapConstants.HTC.equals(institutionEntity.getInstitutionCode()))) {
                requestingInstitutions.add(institutionEntity.getInstitutionCode());
            }
        }

        Iterable<RequestTypeEntity> requestTypeEntities = getRequestTypeDetailsRepository().findAll();
        for (Iterator iterator = requestTypeEntities.iterator(); iterator.hasNext(); ) {
            RequestTypeEntity requestTypeEntity = (RequestTypeEntity) iterator.next();
            if (!RecapConstants.BORROW_DIRECT.equals(requestTypeEntity.getRequestTypeCode())) {
                requestTypes.add(requestTypeEntity.getRequestTypeCode());
            }
        }

        Iterable<CustomerCodeEntity> customerCodeEntities = getCustomerCodeDetailsRepository().findAll();
        for (Iterator iterator = customerCodeEntities.iterator(); iterator.hasNext(); ) {
            CustomerCodeEntity customerCodeEntity = (CustomerCodeEntity) iterator.next();
            if (userDetailsForm.getLoginInstitutionId() == customerCodeEntity.getOwningInstitutionId() || userDetailsForm.isRecapUser() || userDetailsForm.isSuperAdmin()) {
                deliveryLocations.add(customerCodeEntity);
            }
        }

        requestForm.setRequestingInstitutions(requestingInstitutions);
        requestForm.setRequestTypes(requestTypes);
        requestForm.setDeliveryLocations(deliveryLocations);
        requestForm.setRequestType(RecapConstants.RETRIEVAL);
        return requestForm;
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=populateItem")
    public String populateItem(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                               BindingResult result,
                               Model model, HttpServletRequest request) throws JSONException {
        JSONObject jsonObject = new JSONObject();

        if (StringUtils.isNotBlank(requestForm.getItemBarcodeInRequest())) {
            List<String> itemBarcodes = Arrays.asList(requestForm.getItemBarcodeInRequest().split(","));
            List<String> invalidBarcodes = new ArrayList<>();
            Set<String> itemTitles = new HashSet<>();
            Set<String> itemOwningInstitutions = new HashSet<>();
            Map<String,String> deliveryLocationsMap = new LinkedHashMap<>();
            UserDetailsForm userDetailsForm;
            for (String itemBarcode : itemBarcodes) {
                String barcode = itemBarcode.trim();
                if (StringUtils.isNotBlank(barcode)) {
                    List<ItemEntity> itemEntities = getItemDetailsRepository().findByBarcodeAndCatalogingStatusAndIsDeletedFalse(barcode, RecapConstants.COMPLETE_STATUS);
                    if (CollectionUtils.isNotEmpty(itemEntities)) {
                        for (ItemEntity itemEntity : itemEntities) {
                            if (null != itemEntity && CollectionUtils.isNotEmpty(itemEntity.getBibliographicEntities())) {
                                userDetailsForm = getUserAuthUtil().getUserDetails(request.getSession(), RecapConstants.REQUEST_PRIVILEGE);
                                if (itemEntity.getCollectionGroupId() == RecapConstants.CGD_PRIVATE && !userDetailsForm.isSuperAdmin() && !userDetailsForm.isRecapUser() && !userDetailsForm.getLoginInstitutionId().equals(itemEntity.getOwningInstitutionId())) {
                                    jsonObject.put(RecapConstants.NO_PERMISSION_ERROR_MESSAGE, RecapConstants.REQUEST_PRIVATE_ERROR_USER_NOT_PERMITTED);
                                    return jsonObject.toString();
                                } else if (!userDetailsForm.isRecapPermissionAllowed()) {
                                    jsonObject.put(RecapConstants.NO_PERMISSION_ERROR_MESSAGE, RecapConstants.REQUEST_ERROR_USER_NOT_PERMITTED);
                                    return jsonObject.toString();
                                } else {
                                    Integer institutionId = itemEntity.getInstitutionEntity().getInstitutionId();
                                    String institutionCode = itemEntity.getInstitutionEntity().getInstitutionCode();
                                    for (BibliographicEntity bibliographicEntity : itemEntity.getBibliographicEntities()) {
                                        String bibContent = new String(bibliographicEntity.getContent());
                                        BibJSONUtil bibJSONUtil = new BibJSONUtil();
                                        List<Record> records = bibJSONUtil.convertMarcXmlToRecord(bibContent);
                                        Record marcRecord = records.get(0);
                                        itemTitles.add(bibJSONUtil.getTitle(marcRecord));
                                        itemOwningInstitutions.add(institutionCode);
                                    }
                                    String customerCode = itemEntity.getCustomerCode();
                                    CustomerCodeEntity customerCodeEntity = customerCodeDetailsRepository.findByCustomerCodeAndOwningInstitutionId(customerCode, institutionId);
                                    if (customerCodeEntity != null) {
                                        String deliveryRestrictions = customerCodeEntity.getDeliveryRestrictions();
                                        if (StringUtils.isNotBlank(deliveryRestrictions)) {
                                            String[] deliveryLocationsArray = deliveryRestrictions.split(",");
                                            List<CustomerCodeEntity> customerCodeEntities = customerCodeDetailsRepository.findByCustomerCodeIn(Arrays.asList(deliveryLocationsArray));
                                            if (CollectionUtils.isNotEmpty(customerCodeEntities)) {
                                                Collections.sort(customerCodeEntities);
                                                for (CustomerCodeEntity byCustomerCode : customerCodeEntities) {
                                                    deliveryLocationsMap.put(byCustomerCode.getCustomerCode(), byCustomerCode.getDescription());
                                                }
                                            }
                                        } else {
                                            deliveryLocationsMap.put(customerCodeEntity.getCustomerCode(), customerCodeEntity.getDescription());
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        invalidBarcodes.add(barcode);
                    }
                }
            }
            if (CollectionUtils.isNotEmpty(itemTitles)) {
                jsonObject.put(RecapConstants.REQUESTED_ITEM_TITLE, StringUtils.join(itemTitles, " || "));
            }
            if (CollectionUtils.isNotEmpty(itemOwningInstitutions)) {
                jsonObject.put(RecapConstants.REQUESTED_ITEM_OWNING_INSTITUTION, StringUtils.join(itemOwningInstitutions, ","));
            }
            if (CollectionUtils.isNotEmpty(invalidBarcodes)) {
                jsonObject.put(RecapConstants.ERROR_MESSAGE, RecapConstants.BARCODES_NOT_FOUND + " - " + StringUtils.join(invalidBarcodes, ","));
            }
            if (null != deliveryLocationsMap) {
                jsonObject.put(RecapConstants.DELIVERY_LOCATION, deliveryLocationsMap);
            }
        }
        return jsonObject.toString();
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=createRequest")
    public String createRequest(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                BindingResult result,
                                Model model, HttpServletRequest request) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        String customerCodeDescription = null;
        try {
            HttpSession session = request.getSession();
            String username = (String) session.getAttribute(RecapConstants.USER_NAME);
            String stringJson = populateItem(requestForm, null, model, request);
            if (stringJson != null) {
                JSONObject responseJsonObject = new JSONObject(stringJson);
                Object errorMessage = responseJsonObject.has(RecapConstants.ERROR_MESSAGE) ? responseJsonObject.get(RecapConstants.ERROR_MESSAGE) : null;
                Object noPermissionErrorMessage = responseJsonObject.has(RecapConstants.NO_PERMISSION_ERROR_MESSAGE) ? responseJsonObject.get(RecapConstants.NO_PERMISSION_ERROR_MESSAGE) : null;
                if (noPermissionErrorMessage != null) {
                    requestForm.setErrorMessage((String) noPermissionErrorMessage);
                    jsonObject.put(RecapConstants.ERROR_MESSAGE, requestForm.getErrorMessage());
                    return jsonObject.toString();
                } else if (errorMessage != null) {
                    requestForm.setErrorMessage((String) errorMessage);
                    jsonObject.put(RecapConstants.ERROR_MESSAGE, requestForm.getErrorMessage());
                    return jsonObject.toString();
                }
            }

            String requestItemUrl = getServerProtocol() + getScsbUrl() + RecapConstants.REQUEST_ITEM_URL;

            ItemRequestInformation itemRequestInformation = getItemRequestInformation();
            itemRequestInformation.setUsername(username);
            itemRequestInformation.setItemBarcodes(Arrays.asList(requestForm.getItemBarcodeInRequest().split(",")));
            itemRequestInformation.setPatronBarcode(requestForm.getPatronBarcodeInRequest());
            itemRequestInformation.setRequestingInstitution(requestForm.getRequestingInstitution());
            itemRequestInformation.setEmailAddress(requestForm.getPatronEmailAddress());
            itemRequestInformation.setTitle(requestForm.getItemTitle());
            itemRequestInformation.setTitleIdentifier(requestForm.getItemTitle());
            itemRequestInformation.setItemOwningInstitution(requestForm.getItemOwningInstitution());
            itemRequestInformation.setRequestType(requestForm.getRequestType());
            itemRequestInformation.setRequestNotes(requestForm.getRequestNotes());
            itemRequestInformation.setStartPage(requestForm.getStartPage());
            itemRequestInformation.setEndPage(requestForm.getEndPage());
            itemRequestInformation.setAuthor(requestForm.getArticleAuthor());
            itemRequestInformation.setChapterTitle(requestForm.getArticleTitle());
            itemRequestInformation.setIssue(requestForm.getIssue());
            if (requestForm.getVolumeNumber() != null) {
                itemRequestInformation.setVolume(requestForm.getVolumeNumber().toString());
            } else {
                itemRequestInformation.setVolume("");
            }

            if (StringUtils.isNotBlank(requestForm.getDeliveryLocationInRequest())) {
                CustomerCodeEntity customerCodeEntity = getCustomerCodeDetailsRepository().findByCustomerCode(requestForm.getDeliveryLocationInRequest());
                if (null != customerCodeEntity) {
                    customerCodeDescription = customerCodeEntity.getDescription();
                    itemRequestInformation.setDeliveryLocation(customerCodeEntity.getCustomerCode());
                }
            }

            HttpEntity<ItemRequestInformation> requestEntity = new HttpEntity<>(itemRequestInformation, getHttpHeaders());
            ResponseEntity<ItemResponseInformation> itemResponseEntity = getRestTemplate().exchange(requestItemUrl, HttpMethod.POST, requestEntity, ItemResponseInformation.class);
            ItemResponseInformation itemResponseInformation = itemResponseEntity.getBody();
            if (null != itemResponseInformation && !itemResponseInformation.isSuccess()) {
                requestForm.setErrorMessage(itemResponseInformation.getScreenMessage());
            }
        } catch (HttpClientErrorException httpException) {
            logger.error(RecapConstants.LOG_ERROR, httpException);
            String responseBodyAsString = httpException.getResponseBodyAsString();
            requestForm.setErrorMessage(responseBodyAsString);
        } catch (Exception exception) {
            logger.error(RecapConstants.LOG_ERROR, exception);
            requestForm.setErrorMessage(exception.getMessage());
        }

        jsonObject.put(RecapConstants.ITEM_TITLE, requestForm.getItemTitle());
        jsonObject.put(RecapConstants.ITEM_BARCODE, requestForm.getItemBarcodeInRequest());
        jsonObject.put(RecapConstants.ITEM_OWNING_INSTITUTION, requestForm.getItemOwningInstitution());
        jsonObject.put(RecapConstants.PATRON_BARCODE, requestForm.getPatronBarcodeInRequest());
        jsonObject.put(RecapConstants.PATRON_EMAIL_ADDRESS, requestForm.getPatronEmailAddress());
        jsonObject.put(RecapConstants.REQUESTING_INSTITUTION, requestForm.getRequestingInstitution());
        jsonObject.put(RecapConstants.REQUEST_TYPE, requestForm.getRequestType());
        jsonObject.put(RecapConstants.DELIVERY_LOCATION, customerCodeDescription);
        jsonObject.put(RecapConstants.REQUEST_NOTES, requestForm.getRequestNotes());
        jsonObject.put(RecapConstants.ERROR_MESSAGE, requestForm.getErrorMessage());

        return jsonObject.toString();
    }

    @ResponseBody
    @RequestMapping(value = "/request", method = RequestMethod.POST, params = "action=cancelRequest")
    public String cancelRequest(@Valid @ModelAttribute("requestForm") RequestForm requestForm,
                                BindingResult result,
                                Model model) {
        JSONObject jsonObject = new JSONObject();
        String requestStatus = null;
        try {
            HttpEntity requestEntity = new HttpEntity<>(getHttpHeaders());
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(getServerProtocol() + getScsbUrl() + RecapConstants.URL_REQUEST_CANCEL).queryParam(RecapConstants.REQUEST_ID, requestForm.getRequestId());
            HttpEntity<CancelRequestResponse> responseEntity = getRestTemplate().exchange(builder.build().encode().toUri(), HttpMethod.POST, requestEntity, CancelRequestResponse.class);
            CancelRequestResponse cancelRequestResponse = responseEntity.getBody();
            jsonObject.put(RecapConstants.MESSAGE, cancelRequestResponse.getScreenMessage());
            jsonObject.put(RecapConstants.STATUS, cancelRequestResponse.isSuccess());
            RequestItemEntity requestItemEntity = getRequestItemDetailsRepository().findByRequestId(requestForm.getRequestId());
            if (null != requestItemEntity) {
                requestStatus = requestItemEntity.getRequestStatusEntity().getRequestStatusDescription();
            }
            jsonObject.put(RecapConstants.REQUEST_STATUS, requestStatus);
        } catch (Exception exception) {
            logger.error(RecapConstants.LOG_ERROR, exception);
            logger.debug(exception.getMessage());
        }
        return jsonObject.toString();
    }

    private void searchAndSetResults(RequestForm requestForm) {
        Page<RequestItemEntity> requestItemEntities = getRequestServiceUtil().searchRequests(requestForm);
        List<SearchResultRow> searchResultRows = buildSearchResultRows(requestItemEntities.getContent());
        if (CollectionUtils.isNotEmpty(searchResultRows)) {
            requestForm.setSearchResultRows(searchResultRows);
            requestForm.setTotalRecordsCount(NumberFormat.getNumberInstance().format(requestItemEntities.getTotalElements()));
            requestForm.setTotalPageCount(requestItemEntities.getTotalPages());
        } else {
            requestForm.setSearchResultRows(Collections.emptyList());
            requestForm.setMessage(RecapConstants.SEARCH_RESULT_ERROR_NO_RECORDS_FOUND);
        }
        requestForm.setShowResults(true);
    }

    private List<SearchResultRow> buildSearchResultRows(List<RequestItemEntity> requestItemEntities) {
        if (CollectionUtils.isNotEmpty(requestItemEntities)) {
            List<SearchResultRow> searchResultRows = new ArrayList<>();
            for (RequestItemEntity requestItemEntity : requestItemEntities) {
                SearchResultRow searchResultRow = new SearchResultRow();
                searchResultRow.setRequestId(requestItemEntity.getRequestId());
                searchResultRow.setPatronBarcode(requestItemEntity.getPatronId());
                searchResultRow.setRequestingInstitution(requestItemEntity.getInstitutionEntity().getInstitutionCode());
                searchResultRow.setBarcode(requestItemEntity.getItemEntity().getBarcode());
                searchResultRow.setOwningInstitution(requestItemEntity.getItemEntity().getInstitutionEntity().getInstitutionCode());
                searchResultRow.setDeliveryLocation(requestItemEntity.getStopCode());
                searchResultRow.setRequestType(requestItemEntity.getRequestTypeEntity().getRequestTypeCode());
                searchResultRow.setRequestCreatedBy(requestItemEntity.getCreatedBy());
                searchResultRow.setPatronEmailId(requestItemEntity.getEmailId());
                searchResultRow.setRequestNotes(requestItemEntity.getNotes());
                searchResultRow.setCreatedDate(requestItemEntity.getCreatedDate());
                searchResultRow.setStatus(requestItemEntity.getRequestStatusEntity().getRequestStatusDescription());

                ItemEntity itemEntity = requestItemEntity.getItemEntity();
                if (null != itemEntity && CollectionUtils.isNotEmpty(itemEntity.getBibliographicEntities())) {
                    searchResultRow.setBibId(itemEntity.getBibliographicEntities().get(0).getBibliographicId());
                }
                searchResultRows.add(searchResultRow);
            }
            return searchResultRows;
        }
        return Collections.emptyList();
    }

    private HttpHeaders getHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(RecapConstants.API_KEY, RecapConstants.RECAP);
        return headers;
    }

    public Integer getPageNumberOnPageSizeChange(RequestForm requestForm) {
        int totalRecordsCount;
        Integer pageNumber = requestForm.getPageNumber();
        try {
            totalRecordsCount = NumberFormat.getNumberInstance().parse(requestForm.getTotalRecordsCount()).intValue();
            int totalPagesCount = (int) Math.ceil((double) totalRecordsCount / (double) requestForm.getPageSize());
            if (totalPagesCount > 0 && pageNumber >= totalPagesCount) {
                pageNumber = totalPagesCount - 1;
            }
        } catch (Exception e) {
            logger.error(RecapConstants.LOG_ERROR, e);
        }
        return pageNumber;
    }

    public ItemRequestInformation getItemRequestInformation() {
        return new ItemRequestInformation();
    }
}

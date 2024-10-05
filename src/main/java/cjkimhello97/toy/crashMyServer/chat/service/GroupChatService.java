package cjkimhello97.toy.crashMyServer.chat.service;

import static cjkimhello97.toy.crashMyServer.chat.exception.ChatExceptionType.CHAT_ROOM_NOT_FOUND;
import static cjkimhello97.toy.crashMyServer.chat.exception.ChatExceptionType.MEMBER_CHAT_ROOM_TABLE_NOT_EXIST;
import static cjkimhello97.toy.crashMyServer.chat.utils.GroupChatMessageUtils.enterGroupChatRoomMessage;
import static cjkimhello97.toy.crashMyServer.chat.utils.GroupChatMessageUtils.leaveGroupChatRoomMessage;

import cjkimhello97.toy.crashMyServer.chat.controller.dto.ChatMessageResponse;
import cjkimhello97.toy.crashMyServer.chat.controller.dto.GroupChatMessageResponse;
import cjkimhello97.toy.crashMyServer.chat.controller.dto.GroupChatMessageResponses;
import cjkimhello97.toy.crashMyServer.chat.controller.dto.GroupChatRoomResponse;
import cjkimhello97.toy.crashMyServer.chat.domain.ChatMessage;
import cjkimhello97.toy.crashMyServer.chat.domain.ChatRoom;
import cjkimhello97.toy.crashMyServer.chat.domain.MemberChatRoom;
import cjkimhello97.toy.crashMyServer.chat.exception.ChatException;
import cjkimhello97.toy.crashMyServer.chat.repository.ChatMessageRepository;
import cjkimhello97.toy.crashMyServer.chat.repository.ChatRoomRepository;
import cjkimhello97.toy.crashMyServer.chat.repository.MemberChatRoomRepository;
import cjkimhello97.toy.crashMyServer.chat.service.dto.GroupChatMessageRequest;
import cjkimhello97.toy.crashMyServer.kafka.dto.KafkaChatMessageRequest;
import cjkimhello97.toy.crashMyServer.member.domain.Member;
import cjkimhello97.toy.crashMyServer.member.service.MemberService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GroupChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemberChatRoomRepository memberChatRoomRepository;
    private final MemberService memberService;
    private final ModelMapper modelMapper;
    private final KafkaTemplate<String, KafkaChatMessageRequest> kafkaChatMessageRequestTemplate;

    @Transactional
    public Long createGroupChatRoom(Long senderId, String chatRoomName) {
        Member sender = memberService.getMemberByMemberId(senderId);
        ChatRoom chatRoom = ChatRoom.builder()
                .host(sender)
                .chatRoomName(chatRoomName)
                .build();
        ChatRoom savedChatRoom = chatRoomRepository.save(chatRoom);

        enterGroupChatRoom(savedChatRoom.getChatRoomId(), senderId);
        return savedChatRoom.getChatRoomId();
    }

    @Transactional
    public void enterGroupChatRoom(Long chatRoomId, Long senderId) {
        String senderNickname = memberService.getMemberNicknameByMemberId(senderId);
        KafkaChatMessageRequest kafkaRequest = KafkaChatMessageRequest.builder()
                .chatRoomId(chatRoomId)
                .senderId(senderId)
                .senderNickname(senderNickname)
                .content(enterGroupChatRoomMessage(senderNickname))
                .build();
        kafkaChatMessageRequestTemplate.send("enter", kafkaRequest);

        ChatRoom chatRoom = getChatRoomByChatRoomId(chatRoomId);
        Member sender = memberService.getMemberByMemberId(senderId);
        sender.addChatRoom(chatRoom);

        // joinedAt이 null로 저장되는 문제 발생 -> @CreatedDate, @PrePersist 실패 -> 직접 중간 테이블 저장
        MemberChatRoom memberChatRoom = getMemberChatRoomByMemberIdAndChatRoomId(senderId, chatRoomId);
        memberChatRoom.setJoinedAt(LocalDateTime.now());
    }

    @Transactional
    public void saveGroupChatMessage(GroupChatMessageRequest groupChatMessageRequest) {
        groupChatMessageRequest.setCreatedAtNow();
        Member member = memberService.getMemberByNickname(groupChatMessageRequest.getSenderNickname());
        KafkaChatMessageRequest kafkaRequest = modelMapper.map(groupChatMessageRequest, KafkaChatMessageRequest.class);
        kafkaRequest.setSenderId(member.getMemberId());
        kafkaRequest.setChatRoomId(groupChatMessageRequest.getChatRoomId());
        kafkaChatMessageRequestTemplate.send("group-chat", kafkaRequest);

        ChatMessage chatMessage = ChatMessage.builder()
                .chatRoomId(groupChatMessageRequest.getChatRoomId())
                .senderNickname(groupChatMessageRequest.getSenderNickname())
                .senderId(groupChatMessageRequest.getSenderId())
                .content(groupChatMessageRequest.getContent())
                .createdAt(groupChatMessageRequest.getCreatedAt())
                .build();
        chatMessageRepository.save(chatMessage);
    }

    public Set<GroupChatRoomResponse> getGroupChatRooms(Long senderId) {
        Member sender = memberService.getMemberByMemberId(senderId);
        Set<GroupChatRoomResponse> groupChatRoomResponses = new HashSet<>();
        sender.getChatRooms().forEach(chatRoom -> {
            GroupChatRoomResponse groupChatRoomResponse = GroupChatRoomResponse.from(chatRoom);
            ChatMessageResponse chatMessageResponse = modelMapper
                    .map(chatMessageRepository.findFirstByChatRoomIdOrderByCreatedAtDesc(
                                    chatRoom.getChatRoomId()),
                            ChatMessageResponse.class
                    );
            groupChatRoomResponse.setChatMessageResponse(chatMessageResponse);
            groupChatRoomResponses.add(groupChatRoomResponse);
        });
        return groupChatRoomResponses;
    }

    public GroupChatMessageResponses getGroupChatMessages(Long chatRoomId, Long senderId) {
        MemberChatRoom memberChatRoom = getMemberChatRoomByMemberIdAndChatRoomId(senderId, chatRoomId);
        List<GroupChatMessageResponse> groupChatMessageResponses = chatMessageRepository
                .findAllByChatRoomIdAndCreatedAtGreaterThanEqual(chatRoomId, memberChatRoom.getJoinedAt())
                .stream()
                .map(chatMessage -> modelMapper.map(chatMessage, GroupChatMessageResponse.class))
                .collect(Collectors.toList());
        return new GroupChatMessageResponses(groupChatMessageResponses);
    }

    @Transactional
    public void leaveGroupChatRoom(Long chatRoomId, Long senderId) {
        String senderNickname = memberService.getMemberNicknameByMemberId(senderId);

        KafkaChatMessageRequest kafkaRequest = KafkaChatMessageRequest.builder()
                .chatRoomId(chatRoomId)
                .senderId(senderId)
                .senderNickname(senderNickname)
                .content(leaveGroupChatRoomMessage(senderNickname))
                .build();
        kafkaChatMessageRequestTemplate.send("leave", kafkaRequest);

        ChatRoom chatRoom = getChatRoomByChatRoomId(chatRoomId);
        Member sender = memberService.getMemberByMemberId(senderId);
        sender.removeChatRoom(chatRoom);
    }

    public ChatRoom getChatRoomByChatRoomId(Long chatRoomId) {
        return chatRoomRepository.findByChatRoomId(chatRoomId)
                .orElseThrow(() -> new ChatException(CHAT_ROOM_NOT_FOUND));
    }

    private MemberChatRoom getMemberChatRoomByMemberIdAndChatRoomId(Long memberId, Long chatRoomId) {
        return memberChatRoomRepository.findByMemberMemberIdAndChatRoomChatRoomId(memberId, chatRoomId)
                .orElseThrow(() -> new ChatException(MEMBER_CHAT_ROOM_TABLE_NOT_EXIST));
    }
}

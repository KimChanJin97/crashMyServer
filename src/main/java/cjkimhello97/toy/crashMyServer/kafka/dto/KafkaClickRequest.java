package cjkimhello97.toy.crashMyServer.kafka.dto;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KafkaClickRequest implements Serializable {

    private String uuid;
    private String nickname;
    private String count;
}
